package com.example

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object RapidOcrService {
    private const val SERVICE_URL = "http://127.0.0.1:8000/ocr"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var serverProcess: Process? = null

    fun startServer() {
        try {
            val processBuilder = ProcessBuilder("py", "python_ocr/ocr_server.py")
                .redirectErrorStream(true)

            serverProcess = processBuilder.start()

            // Выводим логи Python-сервера в консоль IDEA
            Thread {
                serverProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { println("[Python OCR]: $it") }
                }
            }.start()

            Runtime.getRuntime().addShutdownHook(Thread {
                serverProcess?.destroy()
                println("🛑 Локальный сервер RapidOCR остановлен.")
            })

            println("🚀 Локальный сервер RapidOCR запущен в фоне...")
        } catch (e: Exception) {
            println("⚠️ Не удалось запустить RapidOCR автоматически: ${e.message}")
        }
    }

    fun extractText(imageFile: File): String {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    imageFile.name,
                    imageFile.asRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(SERVICE_URL)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("⚠️ RapidOCR вернул HTTP код: ${response.code}")
                    return ""
                }
                val body = response.body?.string() ?: return ""
                val json = gson.fromJson(body, JsonObject::class.java)
                val text = json.get("text")?.asString ?: ""
                println(">>> [RAPID OCR RAW]:\n$text\n-------------------")
                text
            }
        } catch (e: Exception) {
            println("⚠️ RapidOCR недоступен: ${e.message}")
            ""
        }
    }
}