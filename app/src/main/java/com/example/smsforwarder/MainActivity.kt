package com.example.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var etWebhookUrl: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var etSenderFilter: EditText
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnViewSms: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvQueueStatus: TextView

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val queueSize = intent.getIntExtra("queue_size", 0)
            updateQueueStatusText(queueSize)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("sms_forwarder", MODE_PRIVATE)

        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etDeviceName = findViewById(R.id.etDeviceName)
        etSenderFilter = findViewById(R.id.etSenderFilter)
        switchEnabled = findViewById(R.id.switchEnabled)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        btnViewSms = findViewById(R.id.btnViewSms)
        tvStatus = findViewById(R.id.tvStatus)
        tvQueueStatus = findViewById(R.id.tvQueueStatus)

        loadSettings()
        setupListeners()
        checkPermissions()
        updateQueueStatus()

        val filter = IntentFilter("com.example.smsforwarder.QUEUE_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(queueReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(queueReceiver, filter)
        }

        startQueueService()
    }

    private fun loadSettings() {
        etWebhookUrl.setText(sharedPreferences.getString("webhook_url", ""))
        etDeviceName.setText(sharedPreferences.getString("device_name", "SMSForwarder"))
        etSenderFilter.setText(sharedPreferences.getString("sender_filter", ""))
        switchEnabled.isChecked = sharedPreferences.getBoolean("enabled", true)
        updateStatus()
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { saveSettings() }
        btnTest.setOnClickListener { testWebhook() }
        switchEnabled.setOnCheckedChangeListener { _, _ -> saveSettings() }
        btnViewSms.setOnClickListener {
            startActivity(Intent(this, SmsListActivity::class.java))
        }
    }

    private fun saveSettings() {
        val url = etWebhookUrl.text.toString().trim()
        val deviceName = etDeviceName.text.toString().trim().ifEmpty { "SMSForwarder" }
        val senderFilter = etSenderFilter.text.toString().trim()
        val enabled = switchEnabled.isChecked

        sharedPreferences.edit().apply {
            putString("webhook_url", url)
            putString("device_name", deviceName)
            putString("sender_filter", senderFilter)
            putBoolean("enabled", enabled)
            apply()
        }

        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        updateStatus()
        startQueueService()
    }

    private fun startQueueService() {
        val enabled = sharedPreferences.getBoolean("enabled", false)
        val url = sharedPreferences.getString("webhook_url", "")

        if (enabled && !url.isNullOrEmpty()) {
            val intent = Intent(this, QueueSenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun testWebhook() {
        val url = etWebhookUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Введите URL", Toast.LENGTH_SHORT).show()
            return
        }

        val testSms = SmsData(
            sender = "+79991234567",
            message = "Тестовое сообщение",
            timestamp = System.currentTimeMillis()
        )

        SmsForwarder.forwardSms(this, url, testSms) { success, error ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Тест успешен!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Ошибка: ${error ?: "неизвестно"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStatus() {
        val enabled = sharedPreferences.getBoolean("enabled", false)
        val url = sharedPreferences.getString("webhook_url", "")
        tvStatus.text = when {
            enabled && !url.isNullOrEmpty() -> "Активен\n$url"
            !enabled -> "Отключен"
            else -> "URL не настроен"
        }
    }

    private fun updateQueueStatus() {
        val dbHelper = SmsDatabaseHelper(this)
        try {
            updateQueueStatusText(dbHelper.getQueueCount())
        } finally {
            dbHelper.close()
        }
    }

    private fun updateQueueStatusText(queueSize: Int) {
        if (queueSize > 0) {
            tvQueueStatus.text = "В очереди: $queueSize сообщений"
            tvQueueStatus.setTextColor(0xFFFF9800.toInt())
        } else {
            tvQueueStatus.text = "Очередь пуста"
            tvQueueStatus.setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateQueueStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(queueReceiver)
        } catch (_: Exception) {
        }
    }
}
