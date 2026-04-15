package com.example.smsforwarder

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.*

class SmsListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val messagesList = mutableListOf<String>()
    private val messagesData = mutableListOf<SmsMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_list)

        supportActionBar?.title = "SMS Сообщения"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.listViewSms)

        loadMessages()

        listView.setOnItemClickListener { _, _, position, _ ->
            val sms = messagesData[position]

            // Отмечаем как прочитанное
            val dbHelper = SmsDatabaseHelper(this)
            try {
                dbHelper.markAsRead(sms.id)
            } finally {
                dbHelper.close()
            }

            loadMessages()
            showMessageDialog(sms)
        }
    }

    private fun loadMessages() {
        messagesList.clear()
        messagesData.clear()

        val dbHelper = SmsDatabaseHelper(this)
        try {
            val messages = dbHelper.getAllSms()
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

            messages.forEach { sms ->
                val dateStr = dateFormat.format(Date(sms.timestamp))
                val readMark = if (sms.isRead) "" else " ✉️ НОВОЕ"
                val forwardedMark = if (sms.isForwarded) " 📤" else ""

                val displayText = "$dateStr\nОт: ${sms.sender}\n${sms.message.take(50)}${if (sms.message.length > 50) "..." else ""}$readMark$forwardedMark"

                messagesList.add(displayText)
                messagesData.add(sms)
            }

            if (messagesList.isEmpty()) {
                messagesList.add("Нет сообщений")
            }

            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, messagesList)
            listView.adapter = adapter

            val unreadCount = dbHelper.getUnreadCount()
            if (unreadCount > 0) {
                supportActionBar?.subtitle = "Непрочитанных: $unreadCount"
            } else {
                supportActionBar?.subtitle = null
            }
        } finally {
            dbHelper.close()
        }
    }

    private fun showMessageDialog(sms: SmsMessage) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val dateStr = dateFormat.format(Date(sms.timestamp))

        val message = """
            От: ${sms.sender}
            Время: $dateStr
            Статус: ${if (sms.isForwarded) "Переслано" else "Не переслано"}
            
            Текст:
            ${sms.message}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("SMS сообщение")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> }
            .setNeutralButton("Удалить") { _, _ ->
                // Здесь можно добавить удаление
                Toast.makeText(this, "Удаление пока не реализовано", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        loadMessages()
    }
}