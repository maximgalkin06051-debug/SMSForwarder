package com.example.smsforwarder

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SmsForwardService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sms_forwarder_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val sender = it.getStringExtra("sender") ?: return START_STICKY
            val message = it.getStringExtra("message") ?: return START_STICKY
            val timestamp = it.getLongExtra("timestamp", System.currentTimeMillis())

            val prefs = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
            val webhookUrl = prefs.getString("webhook_url", "")
            val enabled = prefs.getBoolean("enabled", true)

            if (enabled && !webhookUrl.isNullOrEmpty()) {
                val smsData = SmsData(sender, message, timestamp)
                SmsForwarder.forwardSms(this, webhookUrl, smsData) { success, error ->
                    if (!success) {
                        android.util.Log.e("SmsForwardService", "Ошибка: $error")
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarder",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Forwarder")
            .setContentText("Активен")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}