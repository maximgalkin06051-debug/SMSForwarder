package com.example.smsforwarder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class QueueSenderService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "sms_queue_channel"
        private const val INTERVAL_MS = 15000L
    }

    private lateinit var databaseHelper: SmsDatabaseHelper
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastQueueSize = -1

    override fun onCreate() {
        super.onCreate()
        databaseHelper = SmsDatabaseHelper(this)
        createNotificationChannel()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder:QueueSender")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true

            // ===== ИСПРАВЛЕННЫЙ КОД =====
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            startQueueProcessor()
        }
        return START_STICKY
    }

    private fun startQueueProcessor() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    if (isNetworkAvailable()) {
                        processQueue()
                    }
                    delay(INTERVAL_MS)
                } catch (e: Exception) {
                    android.util.Log.e("QueueSender", "Ошибка: ${e.message}")
                }
            }
        }
    }

    private suspend fun processQueue() {
        val pendingMessages = databaseHelper.getPendingMessages()

        if (pendingMessages.size != lastQueueSize) {
            lastQueueSize = pendingMessages.size
            updateForegroundNotification()
            sendStatusBroadcast()
        }

        if (pendingMessages.isEmpty()) return

        android.util.Log.d("QueueSender", "Найдено сообщений в очереди: ${pendingMessages.size}")

        val prefs = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "")
        val enabled = prefs.getBoolean("enabled", true)

        if (!enabled || webhookUrl.isNullOrEmpty()) return

        for (message in pendingMessages) {
            if (message.retryCount >= 10) {
                android.util.Log.e("QueueSender", "Слишком много попыток, удаляем сообщение ${message.id}")
                databaseHelper.removeFromQueue(message.id)
                continue
            }

            val smsData = SmsData(message.sender, message.message, message.timestamp)

            val success = suspendCoroutine<Boolean> { continuation ->
                SmsForwarder.forwardSms(this@QueueSenderService, webhookUrl, smsData) { success, _ ->
                    continuation.resume(success)
                }
            }

            if (success) {
                android.util.Log.d("QueueSender", "✅ Отправлено из очереди: ${message.id}")
                databaseHelper.removeFromQueue(message.id)
                databaseHelper.markAsForwarded(message.smsId)
                lastQueueSize = databaseHelper.getQueueCount()
                updateForegroundNotification()
                sendStatusBroadcast()
            } else {
                android.util.Log.e("QueueSender", "❌ Ошибка отправки ${message.id}, увеличиваем счетчик")
                databaseHelper.incrementRetryCount(message.id)
            }

            delay(1000)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            return networkInfo.isConnected
        }
    }

    private fun sendStatusBroadcast() {
        val intent = Intent("com.example.smsforwarder.QUEUE_STATUS")
        intent.putExtra("queue_size", databaseHelper.getQueueCount())
        sendBroadcast(intent)
    }

    private fun updateForegroundNotification() {
        val queueSize = databaseHelper.getQueueCount()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Очередь SMS")
            .setContentText("В очереди: $queueSize сообщений")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Очередь SMS",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val queueSize = databaseHelper.getQueueCount()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Очередь SMS")
            .setContentText("В очереди: $queueSize сообщений")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        wakeLock?.release()
        try {
            databaseHelper.close()
        } catch (e: Exception) {
            android.util.Log.e("QueueSender", "Ошибка закрытия БД: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}