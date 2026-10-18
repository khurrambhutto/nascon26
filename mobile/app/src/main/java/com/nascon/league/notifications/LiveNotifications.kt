package com.nascon.league.notifications

import android.app.*
import android.content.*
import android.net.Uri
import com.nascon.league.MainActivity
import com.nascon.league.R
import kotlinx.serialization.json.*

fun showLiveNotification(context: Context, event: JsonObject): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("live_updates", "Invitations and orders", NotificationManager.IMPORTANCE_HIGH))
    val payload = event.getValue("payload").jsonObject
    val invite = event.getValue("type").jsonPrimitive.content == "costream.invite"
    val link = if (invite) "nascon://stream/${payload.getValue("session_id").jsonPrimitive.content}?invite_token=${payload.getValue("invite_token").jsonPrimitive.content}" else "nascon://orders"
    val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(link))
    val id = event.getValue("event_id").jsonPrimitive.content.hashCode()
    val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val text = if (invite) "You have been invited to broadcast. Tap to join." else "Order ${payload["order_id"]?.jsonPrimitive?.content}: ${payload["status"]?.jsonPrimitive?.content}"
    if (android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        manager.notify(id, Notification.Builder(context, "live_updates").setSmallIcon(R.drawable.ic_match_reminder).setContentTitle(if (invite) "Co-stream invitation" else "Order update").setContentText(text).setContentIntent(pending).setAutoCancel(true).build())
        return true
    }
    return false
}
