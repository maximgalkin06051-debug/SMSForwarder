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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

class QueueSenderService : Service() {

    companion object {
        private const val TAG = "QueueSender"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "sms_queue_channel"
        private const val BASE_INTERVAL_MS = 15_000L
        private const val MAX_INTERVAL_MS = 300_000L // 5 минут макс
        private const val MAX_RETRY_COUNT = 50
        private const val DB_CLEANUP_INTERVAL_MS = 86_400_000L // раз в сутки
        private const val OLD_MESSAGES_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней
    }

    private lateinit var databaseHelper: SmsDatabaseHelper
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastQueueSize = -1
    private var lastCleanupTime = 0L

    override fun onCreate() {
        super.onCreate()
        databaseHelper = SmsDatabaseHelper(this)
        createNotificationChannel()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsForwarder:QueueSender"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true

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
            var currentInterval = BASE_INTERVAL_MS
            while (isRunning) {
                try {
                    if (isNetworkAvailable()) {
                        // Держим WakeLock только на время обработки
                        wakeLock?.acquire(60_000L) // таймаут 60 сек на случай зависания
                        try {
                            val hadFailures = processQueue()
                            // Exponential backoff при ошибках
                            currentInterval = if (hadFailures) {
                                min(currentInterval * 2, MAX_INTERVAL_MS)
                            } else {
                                BASE_INTERVAL_MS
                            }
                        } finally {
                            if (wakeLock?.isHeld == true) {
                                wakeLock?.release()
                            }
                        }
                    }

                    // Периодическая очистка старых записей
                    cleanupOldMessagesIfNeeded()

                    delay(currentInterval)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле обработки: ${e.message}")
                    delay(currentInterval)
                }
            }
        }
    }

    /** @return true если были ошибки отправки */
    private suspend fun processQueue(): Boolean {
        val pendingMessages = databaseHelper.getPendingMessages()

        if (pendingMessages.size != lastQueueSize) {
            lastQueueSize = pendingMessages.size
            updateForegroundNotification()
            sendStatusBroadcast()
        }

        if (pendingMessages.isEmpty()) return false

        Log.d(TAG, "В очереди: ${pendingMessages.size}")

        val prefs = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "")
        val enabled = prefs.getBoolean("enabled", true)

        if (!enabled || webhookUrl.isNullOrEmpty()) return false

        var hadFailures = false

        for (message in pendingMessages) {
            if (message.retryCount >= MAX_RETRY_COUNT) {
                Log.e(TAG, "Превышен лимит попыток ($MAX_RETRY_COUNT), удаляем: ${message.id}")
                databaseHelper.removeFromQueue(message.id)
                continue
            }

            val smsData = SmsData(message.sender, message.message, message.timestamp)

            val success = suspendCoroutine<Boolean> { continuation ->
                SmsForwarder.forwardSms(this@QueueSenderService, webhookUrl, smsData) { ok, _ ->
                    continuation.resume(ok)
                }
            }

            if (success) {
                Log.d(TAG, "Отправлено из очереди: ${message.id}")
                databaseHelper.removeFromQueue(message.id)
                databaseHelper.markAsForwarded(message.smsId)
                lastQueueSize = databaseHelper.getQueueCount()
                updateForegroundNotification()
                sendStatusBroadcast()
            } else {
                Log.e(TAG, "Ошибка отправки ${message.id}, retry=${message.retryCount}")
                databaseHelper.incrementRetryCount(message.id)
                hadFailures = true
                // При ошибке прекращаем обработку — нет смысла долбить лежащий сервер
                break
            }

            delay(1000)
        }

        return hadFailures
    }

    private fun cleanupOldMessagesIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > DB_CLEANUP_INTERVAL_MS) {
            lastCleanupTime = now
            try {
                databaseHelper.deleteOldMessages(OLD_MESSAGES_TTL_MS)
                Log.d(TAG, "Очистка старых сообщений выполнена")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки БД: ${e.message}")
            }
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
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
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
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        try {
            databaseHelper.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка закрытия БД: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
