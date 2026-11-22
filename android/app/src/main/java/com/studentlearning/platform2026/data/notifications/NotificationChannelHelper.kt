package com.studentlearning.platform2026.data.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.studentlearning.platform2026.MainActivity
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.data.remote.firebase.FirebaseClientProvider
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import kotlin.math.abs

object NotificationChannelHelper {
    const val CHANNEL_ID_STUDY_UPDATES = "haqibati_study_updates"
    const val TOPIC_UPDATES = "haqibati_updates"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_STUDY_UPDATES,
            context.getString(R.string.notifications_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notifications_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun subscribeToUpdatesTopicIfConfigured(
        firebaseClientProvider: FirebaseClientProvider
    ) {
        if (!firebaseClientProvider.isFirebaseConfigured()) {
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_UPDATES)
        }
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun showSystemNotificationIfPermitted(
        context: Context,
        item: LocalNotificationItem
    ): Boolean {
        ensureNotificationChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            return false
        }
        val notificationIntId = item.id.hashCode() and 0x7fffffff
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationIntId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STUDY_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationIntId, notification)
        }.isSuccess
    }
}
