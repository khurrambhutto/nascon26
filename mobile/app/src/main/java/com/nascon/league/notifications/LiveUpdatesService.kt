package com.nascon.league.notifications

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.nascon.league.R
import com.nascon.league.data.ServiceLocator

/** Keep the self-hosted notification socket alive while a signed-in app is backgrounded. */
class LiveUpdatesService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("live_connection", "Live connection", NotificationManager.IMPORTANCE_LOW))
        startForeground(4100, Notification.Builder(this, "live_connection")
            .setSmallIcon(R.drawable.ic_match_reminder)
            .setContentTitle("ArenaOne live updates")
            .setContentText("Listening for co-stream invitations and order updates")
            .setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ServiceLocator.repo.session.value == null) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
