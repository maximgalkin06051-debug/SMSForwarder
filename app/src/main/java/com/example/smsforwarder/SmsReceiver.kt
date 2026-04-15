package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import android.widget.Toast

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== onReceive ВЫЗВАН! ===")

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isNullOrEmpty()) return

            // ===== ВАЖНО: СОЗДАЁМ ОДИН ЭКЗЕМПЛЯР БД =====
            val dbHelper = SmsDatabaseHelper(context)

            try {
                messages.forEach { smsMessage ->
                    val sender = smsMessage.displayOriginatingAddress
                    val messageBody = smsMessage.displayMessageBody
                    val timestamp = smsMessage.timestampMillis

                    Log.d(TAG, "SMS от: $sender, текст: $messageBody")

                    // Сохраняем SMS в БД
                    val smsId = dbHelper.saveSms(sender, messageBody, timestamp)
                    Log.d(TAG, "✅ SMS сохранено в БД, ID: $smsId")

                    // Загружаем настройки
                    val prefs = context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
                    val enabled = prefs.getBoolean("enabled", true)
                    val webhookUrl = prefs.getString("webhook_url", "")

                    if (enabled && !webhookUrl.isNullOrEmpty()) {
                        val smsData = SmsData(sender, messageBody, timestamp)
                        SmsForwarder.forwardSms(context, webhookUrl, smsData) { success, error ->
                            if (success) {
                                Log.d(TAG, "✅ SMS отправлено немедленно")
                                dbHelper.markAsForwarded(smsId)
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(context, "SMS отправлено", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Log.e(TAG, "❌ Ошибка отправки, добавляем в очередь: $error")
                                dbHelper.addToQueue(smsId, sender, messageBody, timestamp)
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(context, "SMS добавлено в очередь", Toast.LENGTH_SHORT).show()
                                }

                                // Запускаем сервис очереди
                                val queueIntent = Intent(context, QueueSenderService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(queueIntent)
                                } else {
                                    context.startService(queueIntent)
                                }
                            }
                        }
                    } else if (enabled) {
                        dbHelper.addToQueue(smsId, sender, messageBody, timestamp)
                        Log.d(TAG, "SMS добавлено в очередь (URL не задан)")
                    }
                }
            } finally {
                // ===== ВАЖНО: ЗАКРЫВАЕМ СОЕДИНЕНИЕ С БД =====
                dbHelper.close()
            }
        }
    }
}