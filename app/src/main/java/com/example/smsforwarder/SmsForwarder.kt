package com.example.smsforwarder

import android.content.Context
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

// Класс для SMS данных
data class SmsData(
    val sender: String,
    val message: String,
    val timestamp: Long
)

// Класс для отправки на сервер
data class ForwardData(
    val device: String = "NEWEDEMSMS",
    val phone: String,
    val text: String
)

object SmsForwarder {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun forwardSms(
        context: Context,
        url: String,
        smsData: SmsData,
        callback: (Boolean, String?) -> Unit
    ) {
        // ДЕТАЛЬНОЕ ЛОГИРОВАНИЕ ВХОДНЫХ ДАННЫХ
        android.util.Log.d("SmsForwarder", "========== НАЧАЛО ОТПРАВКИ ==========")
        android.util.Log.d("SmsForwarder", "URL: $url")
        android.util.Log.d("SmsForwarder", "Исходный отправитель: ${smsData.sender}")
        android.util.Log.d("SmsForwarder", "Исходный текст: ${smsData.message}")

        // Очищаем номер телефона
        val cleanPhone = smsData.sender
            .replace("+", "")
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .trim()

        android.util.Log.d("SmsForwarder", "Очищенный номер: $cleanPhone")
        android.util.Log.d("SmsForwarder", "Текст сообщения: ${smsData.message}")

        // Создаем данные для отправки
        val forwardData = ForwardData(
            phone = cleanPhone,
            text = smsData.message
        )

        val json = gson.toJson(forwardData)

        android.util.Log.d("SmsForwarder", "ОТПРАВЛЯЕМЫЙ JSON: $json")
        android.util.Log.d("SmsForwarder", "Длина JSON: ${json.length}")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = RequestBody.create(mediaType, json)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "SMSForwarder/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("SmsForwarder", "ОШИБКА ОТПРАВКИ: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: "нет тела"
                android.util.Log.d("SmsForwarder", "КОД ОТВЕТА: ${response.code}")
                android.util.Log.d("SmsForwarder", "ТЕЛО ОТВЕТА: $responseBody")

                val success = response.isSuccessful
                val error = if (success) null else "HTTP ${response.code}"
                callback(success, error)
                response.close()
            }
        })
    }
}