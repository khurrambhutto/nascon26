package com.nascon.league.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.nascon.league.MainActivity
import com.nascon.league.R
import com.nascon.league.data.AppJson
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.time.Instant

/** Pure timing function, also used by unit tests. No timezone assumptions. */
fun reminderAt(startsAt: String, now: Long): Long? {
    val start = runCatching { Instant.parse(startsAt).toEpochMilli() }.getOrNull() ?: return null
    if (start <= now) return null
    return maxOf(start - 10 * 60_000L, now + 1_000)
}

@Serializable
data class MatchReminder(val owner: String, val matchId: String, val title: String, val startsAt: String)

class MatchReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("match_reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun notificationsAllowed(): Boolean = notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun preciseTimingAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun key(owner: String, matchId: String) = "$owner|$matchId"
    private fun records(): Map<String, MatchReminder> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith("booking:")) null else runCatching {
            key.removePrefix("booking:") to AppJson.decodeFromString(MatchReminder.serializer(), value as String)
        }.getOrNull()
    }.toMap()

    fun get(owner: String, matchId: String): MatchReminder? = records()[key(owner, matchId)]
    fun record(key: String): MatchReminder? = records()[key]
    fun activeOwner(): String = prefs.getString("active_owner", "").orEmpty()

    private fun intent(key: String): Intent = Intent(context, MatchReminderReceiver::class.java)
        .setData(Uri.parse("arenaone://match-reminder/" + Uri.encode(key)))
        .putExtra("reminder_key", key)

    private fun cancelAlarm(key: String) {
        PendingIntent.getBroadcast(context, 0, intent(key), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it); it.cancel()
        }
        notifications.cancel(key, 0)
    }

    fun cancel(owner: String, matchId: String) {
        val key = key(owner, matchId)
        cancelAlarm(key)
        prefs.edit().remove("booking:$key").commit()
    }

    fun enable(owner: String, matchId: String, title: String, startsAt: String) {
        require(notificationsAllowed()) { "Allow notifications in Android settings first." }
        require(preciseTimingAllowed()) { "Allow precise alarms to receive reminders ten minutes before kickoff." }
        require(reminderAt(startsAt, System.currentTimeMillis()) != null) { "This match has already started or has no valid start time." }
        val record = MatchReminder(owner, matchId, title, startsAt)
        prefs.edit().putString("booking:${key(owner, matchId)}", AppJson.encodeToString(MatchReminder.serializer(), record)).commit()
        try { schedule(key(owner, matchId), record) }
        catch (e: Exception) { cancel(owner, matchId); throw e }
    }

    private fun schedule(key: String, record: MatchReminder) {
        val at = reminderAt(record.startsAt, System.currentTimeMillis()) ?: return
        if (!notificationsAllowed() || !preciseTimingAllowed()) return
        val pending = PendingIntent.getBroadcast(context, 0, intent(key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Keep bookings on account switching; only the active account receives notifications. */
    fun restore(owner: String) {
        val old = activeOwner()
        if (old != owner) records().filterValues { it.owner == old }.keys.forEach(::cancelAlarm)
        prefs.edit().putString("active_owner", owner).commit()
        records().forEach { (key, record) ->
            if (record.owner == owner) {
                if (reminderAt(record.startsAt, System.currentTimeMillis()) == null) cancel(record.owner, record.matchId)
                else try { schedule(key, record) } catch (_: SecurityException) { }
            }
        }
    }

    fun update(owner: String, matchId: String, startsAt: String?, status: String) {
        val old = get(owner, matchId) ?: return
        if (status != "upcoming" || startsAt == null || reminderAt(startsAt, System.currentTimeMillis()) == null) {
            cancel(owner, matchId)
        } else if (old.startsAt != startsAt) {
            val changed = old.copy(startsAt = startsAt)
            prefs.edit().putString("booking:${key(owner, matchId)}", AppJson.encodeToString(MatchReminder.serializer(), changed)).commit()
            try { schedule(key(owner, matchId), changed) } catch (_: SecurityException) { }
        }
    }

    fun fire(key: String) {
        val record = record(key) ?: return
        if (record.owner != activeOwner()) return
        val start = runCatching { Instant.parse(record.startsAt).toEpochMilli() }.getOrNull() ?: return
        if (start <= System.currentTimeMillis()) { cancel(record.owner, record.matchId); return }
        if (start - 10 * 60_000L > System.currentTimeMillis() + 2_000) {
            schedule(key, record) // A previously scheduled alarm raced with a reschedule.
            return
        }
        if (!notificationsAllowed()) return
        val channel = "match_reminders"
        notifications.createNotificationChannel(NotificationChannel(channel, "Match reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("arenaone://open-match/" + Uri.encode(key)))
            .putExtra("reminder_match_id", record.matchId)
        val click = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = if (start - System.currentTimeMillis() < 9 * 60_000) "Kickoff is coming up. Tap to open the match." else "Starts in 10 minutes. Tap to open the match."
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_match_reminder).setContentTitle(record.title)
            .setContentText(message).setContentIntent(click).setAutoCancel(true).build()
        notifications.notify(key, 0, notification)
        // Retain no expired booking, and do not emit another notification after a reboot.
        prefs.edit().remove("booking:$key").commit()
    }
}

class MatchReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = MatchReminders(context)
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            reminders.restore(reminders.activeOwner())
            return
        }
        val key = intent.getStringExtra("reminder_key") ?: return
        val record = reminders.record(key) ?: return
        if (record.owner != reminders.activeOwner()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Recheck postponements/cancellations when online, but still notify from the
                // saved schedule if offline. The receiver never waits indefinitely for Wi-Fi.
                val result = withTimeoutOrNull(7_000) { runCatching { ServiceLocator.repo.match(record.matchId) } }
                val error = result?.exceptionOrNull()
                if (error is com.nascon.league.data.ApiException && error.status in setOf(403, 404)) {
                    reminders.cancel(record.owner, record.matchId)
                }
                reminders.fire(key)
            } finally { pending.finish() }
        }
    }
}
