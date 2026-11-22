package com.voicechat.assistant

import android.Manifest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Keeps an explicitly started live microphone session available after a Maps handoff. */
internal class VoiceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSignal.tryEmit(Unit)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live voice", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceChat is listening")
            .setContentText("Tap to return to the conversation.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build())
            .build()
        val hasLocation = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val locationType = if (hasLocation && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val micType = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, locationType or micType)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "live_voice"
        private const val STOP = "com.voicechat.assistant.STOP_LIVE"
        private const val NOTIFICATION_ID = 804
        private val stopSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val stopRequests = stopSignal.asSharedFlow()

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceForegroundService::class.java))
        }
    }
}
