package com.nascon.league.ui.screens

import androidx.compose.material3.OutlinedButton
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nascon.league.data.MatchDetail
import com.nascon.league.data.ServiceLocator
import com.nascon.league.ui.prettyTime

@Composable
fun MatchReminderButton(match: MatchDetail, title: String) {
    val context = LocalContext.current
    val repo = ServiceLocator.repo
    val reminders = repo.reminders
    val owner = repo.reminderOwner
    var enabled by remember(match.match_id, match.scheduled_at, match.status) {
        mutableStateOf(reminders.get(owner, match.match_id) != null)
    }
    var message by remember(match.match_id) { mutableStateOf<String?>(null) }
    LaunchedEffect(match.scheduled_at, match.status) {
        reminders.update(owner, match.match_id, match.scheduled_at, match.status)
        enabled = reminders.get(owner, match.match_id) != null
    }
    fun save() {
        try {
            reminders.enable(owner, match.match_id, title, match.scheduled_at ?: "")
            enabled = true
            val starts = java.time.Instant.parse(match.scheduled_at).toEpochMilli()
            message = if (starts - System.currentTimeMillis() < 10 * 60_000) "Kickoff is less than ten minutes away. You’ll be notified shortly."
                else "Reminder set for ten minutes before kickoff."
        } catch (e: Exception) { message = e.message ?: "The reminder could not be set." }
    }
    val precisePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (reminders.preciseTimingAllowed()) save()
        else message = "Allow alarms and reminders in Android settings to use precise kickoff notifications."
    }
    fun requestPrecise() {
        if (reminders.preciseTimingAllowed()) save()
        else if (Build.VERSION.SDK_INT >= 31) precisePermission.launch(Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestPrecise() else message = "Notifications are blocked. Enable them in Android settings to receive a reminder."
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        enabled = reminders.get(owner, match.match_id) != null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        match.scheduled_at?.let { Text("Kickoff: ${prettyTime(it)}", style = MaterialTheme.typography.bodyMedium) }
        if (match.status == "upcoming" && match.scheduled_at != null || enabled) {
            OutlinedButton(onClick = {
                if (enabled) {
                    reminders.cancel(owner, match.match_id); enabled = false; message = "Reminder removed."
                } else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!reminders.notificationsAllowed()) {
                    message = "Enable notifications in Android settings to receive reminders."
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } else requestPrecise()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("match_remind_me")) {
                Text(if (enabled) "Cancel reminder" else "Remind me · 10 minutes before")
            }
        }
        message?.let { Text(it, modifier = Modifier.testTag("match_reminder_status"), style = MaterialTheme.typography.bodyMedium) }
    }
}
