package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Группируем PDU-части в одно сообщение по отправителю
        val grouped = mutableMapOf<String, StringBuilder>()
        var timestamp = 0L

        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: continue
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.displayMessageBody ?: "")
            if (msg.timestampMillis > timestamp) {
                timestamp = msg.timestampMillis
            }
        }

        val prefs = context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""

        val dbHelper = SmsDatabaseHelper(context)

        try {
            for ((sender, textBuilder) in grouped) {
                val messageBody = textBuilder.toString()
                Log.d(TAG, "SMS от: $sender, длина: ${messageBody.length}")

                val smsId = dbHelper.saveSms(sender, messageBody, timestamp)

                if (enabled && webhookUrl.isNotEmpty()) {
                    // Сразу кладём в очередь — отправкой занимается QueueSenderService.
                    // Это безопаснее, чем делать async HTTP из BroadcastReceiver,
                    // который имеет жёсткий лимит ~10 секунд.
                    dbHelper.addToQueue(smsId, sender, messageBody, timestamp)

                    // Запускаем/будим сервис очереди
                    val queueIntent = Intent(context, QueueSenderService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(queueIntent)
                    } else {
                        context.startService(queueIntent)
                    }
                } else if (enabled) {
                    // URL не задан — всё равно сохраняем в очередь на будущее
                    dbHelper.addToQueue(smsId, sender, messageBody, timestamp)
                    Log.d(TAG, "SMS добавлено в очередь (URL не задан)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки SMS: ${e.message}")
        } finally {
            dbHelper.close()
        }
    }
}
