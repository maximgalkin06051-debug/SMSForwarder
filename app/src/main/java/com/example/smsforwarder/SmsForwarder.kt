package com.example.smsforwarder

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SmsData(
    val sender: String,
    val message: String,
    val timestamp: Long
)

data class ForwardData(
    val device: String,
    val phone: String,
    val text: String
)

object SmsForwarder {
    private const val TAG = "SmsForwarder"

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
        val prefs = context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", "SMSForwarder") ?: "SMSForwarder"

        val cleanPhone = smsData.sender
            .replace("+", "")
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .trim()

        val forwardData = ForwardData(
            device = deviceName,
            phone = cleanPhone,
            text = smsData.message
        )

        val json = gson.toJson(forwardData)
        Log.d(TAG, "Отправка на $url, phone=$cleanPhone, длина текста=${smsData.message.length}")

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
                Log.e(TAG, "Ошибка сети: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val code = resp.code
                    Log.d(TAG, "Ответ: HTTP $code")

                    val success = resp.isSuccessful
                    val error = if (success) null else "HTTP $code"
                    callback(success, error)
                }
            }
        })
    }
}
