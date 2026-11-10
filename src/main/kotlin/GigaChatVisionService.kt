package com.example

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.net.ssl.*

object GigaChatVisionService {

    private const val AUTH_CREDENTIALS = "MDFhMWMzZDctYTRiNi03MWIzLThjOTEtZmFmNGE3NmZjZmU1Ojg4NmNjZTE4LTdmNGItNDk2ZS04NjM3LWI2NTM0Mzc5YTZkMA=="
    private const val SCOPE = "GIGACHAT_API_PERS"

    private const val OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private const val FILES_URL = "https://gigachat.devices.sberbank.ru/api/v1/files"
    private const val CHAT_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
    private const val BALANCE_URL = "https://gigachat.devices.sberbank.ru/api/v1/balance"

    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private val gson = Gson()
    private val client: OkHttpClient = buildUnsafeOkHttpClient()

    /**
     * Анализ одиночного фото оборудования
     */
    fun analyzeDevicePhoto(imageFile: File): ParsedRecognitionResult {
        try {
            ensureAccessToken()

            // Сжимаем фото перед загрузкой (экономит токены и трафик)
            val readyFile = compressImageBeforeUpload(imageFile, maxDimension = 1400)

            val fileId = uploadImage(readyFile) ?: return ParsedRecognitionResult(
                objectType = ObjectType.UNKNOWN,
                manufacturer = "Сбой",
                model = "Не удалось загрузить фото"
            )

            val prompt = """
                Телематика/тахография. Извлеки данные с фото в JSON:
                {
                  "objectType": "TACHOGRAPH_PLATE" | "DUT" | "TERMINAL" | "SIM_CARD" | "UNKNOWN",
                  "manufacturer": "бренд/мастерская/оператор",
                  "model": "модель/тахограф",
                  "serialNumber": "S/N или C/N",
                  "macAddress": "MAC датчика",
                  "imei": "15 цифр IMEI",
                  "phoneNumber": "номер SIM (+7...)",
                  "iccid": "весь ICCID (слитно)",
                  "vin": "VIN ТС",
                  "vrn": "госномер ТС",
                  "nkm": "номер СКЗИ/НКМ"
                }
                Строго валидный JSON, пустые поля "".
            """.trimIndent()

            val responseRaw = askGigaChat(fileId, prompt)
            println(">>> [GIGACHAT RAW]:\n$responseRaw")

            return parseJsonSafely(responseRaw)
        } catch (e: Exception) {
            println("❌ Исключение: ${e.message}")
            e.printStackTrace()
            return ParsedRecognitionResult(
                objectType = ObjectType.UNKNOWN,
                manufacturer = "Ошибка",
                model = "Сбой: ${e.message ?: "неизвестно"}"
            )
        }
    }

    /**
     * Анализ коллажа из нескольких фото (пакетное распознавание за 1 запрос)
     */
    fun analyzeCollagePhotos(collageFile: File): List<ParsedRecognitionResult> {
        try {
            ensureAccessToken()

            val fileId = uploadImage(collageFile) ?: return emptyList()

            val prompt = """
                На фото коллаж (сетка), содержащий оборудование телематики и контроля транспорта.
                Найди ВСЕ отдельные объекты на картинке и заполни по каждому запись.

                ТИПЫ ОБЪЕКТОВ:
                - "TACHOGRAPH_PLATE": калибровочная табличка ТС (vin, vrn, model, serialNumber, nkm, manufacturer)
                - "TERMINAL": трекер ГЛОНАСС (model, serialNumber, imei, manufacturer)
                - "DUT": датчик уровня топлива (model, serialNumber, macAddress)
                - "SIM_CARD": SIM-карта (iccid - все цифры длинного номера слитно; phoneNumber если есть)

                ВЕРНИ ТОЛЬКО JSON-МАССИВ ОБЪЕКТОВ:
                [
                  {
                    "objectType": "TACHOGRAPH_PLATE" | "DUT" | "TERMINAL" | "SIM_CARD",
                    "manufacturer": "бренд/оператор/мастерская",
                    "model": "модель",
                    "serialNumber": "S/N или C/N",
                    "macAddress": "MAC",
                    "imei": "IMEI",
                    "phoneNumber": "номер телефона",
                    "iccid": "ICCID",
                    "vin": "VIN",
                    "vrn": "госномер",
                    "nkm": "номер СКЗИ/НКМ"
                  }
                ]
            """.trimIndent()

            val responseRaw = askGigaChat(fileId, prompt)
            println(">>> [COLLAGE RAW]:\n$responseRaw")

            return parseJsonArraySafely(responseRaw)
        } catch (e: Exception) {
            println("❌ Ошибка коллажа: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Запрос остатка токенов аккаунта
     */
    fun getRemainingBalance(): String {
        return try {
            ensureAccessToken()

            val request = Request.Builder()
                .url(BALANCE_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return "Не удалось получить баланс (${response.code})"
                }

                val json = gson.fromJson(body, JsonObject::class.java)
                val balances = json.getAsJsonArray("balance")

                if (balances != null && balances.size() > 0) {
                    val sb = StringBuilder("📊 <b>Остаток токенов GigaChat:</b>\n")
                    for (b in balances) {
                        val item = b.asJsonObject
                        val usageName = item.get("usage")?.asString ?: "Пакет"
                        val value = item.get("value")?.asLong ?: 0
                        sb.append("• $usageName: <b>$value</b> токенов\n")
                    }
                    sb.toString()
                } else {
                    "Баланс: $body"
                }
            }
        } catch (e: Exception) {
            "Ошибка проверки лимита: ${e.message}"
        }
    }

    private fun compressImageBeforeUpload(originalFile: File, maxDimension: Int = 1400): File {
        try {
            val originalImage = ImageIO.read(originalFile) ?: return originalFile
            val width = originalImage.width
            val height = originalImage.height

            if (width <= maxDimension && height <= maxDimension) return originalFile

            val ratio = width.toDouble() / height.toDouble()
            val newWidth: Int
            val newHeight: Int
            if (width > height) {
                newWidth = maxDimension
                newHeight = (maxDimension / ratio).toInt()
            } else {
                newHeight = maxDimension
                newWidth = (maxDimension * ratio).toInt()
            }

            val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.drawImage(originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()

            val compressedFile = File(originalFile.parentFile, "compressed_${originalFile.name}")
            ImageIO.write(resized, "jpg", compressedFile)
            println(">>> [LOG] Фото сжато: ${width}x${height} -> ${newWidth}x${newHeight}")
            return compressedFile
        } catch (e: Exception) {
            println("⚠️ Не удалось сжать фото, отправляем оригинал: ${e.message}")
            return originalFile
        }
    }

    private fun parseJsonSafely(raw: String): ParsedRecognitionResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            return ParsedRecognitionResult(
                objectType = ObjectType.UNKNOWN,
                manufacturer = "Не определено",
                model = "Не определено",
                rawText = raw
            )
        }

        val jsonStr = raw.substring(start, end + 1)
        return try {
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)

            val typeStr = obj.get("objectType")?.asString ?: "UNKNOWN"
            val type = when (typeStr.uppercase()) {
                "TACHOGRAPH_PLATE" -> ObjectType.TACHOGRAPH_PLATE
                "DUT" -> ObjectType.DUT
                "TERMINAL" -> ObjectType.TERMINAL
                "SIM_CARD" -> ObjectType.SIM_CARD
                else -> ObjectType.UNKNOWN
            }

            fun extractString(field: String): String {
                val element = obj.get(field) ?: return ""
                if (element.isJsonNull) return ""
                val text = element.asString.trim()
                return if (text.equals("null", ignoreCase = true) || text == "-") "" else text
            }

            var cleanIccid = extractString("iccid").filter { it.isLetterOrDigit() }
            var cleanPhone = extractString("phoneNumber").filter { it.isDigit() }

            if (cleanPhone.startsWith("89") || (cleanIccid.isNotBlank() && cleanIccid.startsWith(cleanPhone))) {
                if (cleanIccid.length < 16 && cleanPhone.startsWith("89")) {
                    cleanIccid = cleanPhone + cleanIccid
                }
                cleanPhone = ""
            }

            ParsedRecognitionResult(
                objectType = type,
                manufacturer = extractString("manufacturer").ifBlank { "Не определено" },
                model = extractString("model").ifBlank { "Не определено" },
                serialNumber = extractString("serialNumber").filter { it.isLetterOrDigit() },
                macAddress = extractString("macAddress"),
                imei = extractString("imei").filter { it.isDigit() },
                phoneNumber = cleanPhone,
                iccid = cleanIccid,
                vin = extractString("vin").filter { it.isLetterOrDigit() }.uppercase(),
                vrn = extractString("vrn").replace(" ", "").uppercase(),
                nkm = extractString("nkm").filter { it.isLetterOrDigit() }.uppercase(),
                rawText = extractString("rawText").ifBlank { raw }
            )
        } catch (e: Exception) {
            println("❌ Ошибка парсинга JSON: ${e.message}")
            ParsedRecognitionResult(
                objectType = ObjectType.UNKNOWN,
                manufacturer = "Ошибка парсинга",
                model = "Не удалось прочитать ответ ИИ",
                rawText = raw
            )
        }
    }

    private fun parseJsonArraySafely(raw: String): List<ParsedRecognitionResult> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end == -1 || end <= start) {
            // Если ИИ вернул не массив, а одиночный объект в { }
            val single = parseJsonSafely(raw)
            return if (single.objectType != ObjectType.UNKNOWN) listOf(single) else emptyList()
        }

        val jsonStr = raw.substring(start, end + 1)
        val results = mutableListOf<ParsedRecognitionResult>()

        try {
            val array = gson.fromJson(jsonStr, JsonArray::class.java)
            for (element in array) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject

                val typeStr = obj.get("objectType")?.asString ?: "UNKNOWN"
                val type = when (typeStr.uppercase()) {
                    "TACHOGRAPH_PLATE" -> ObjectType.TACHOGRAPH_PLATE
                    "DUT" -> ObjectType.DUT
                    "TERMINAL" -> ObjectType.TERMINAL
                    "SIM_CARD" -> ObjectType.SIM_CARD
                    else -> ObjectType.UNKNOWN
                }

                fun getVal(key: String): String {
                    val v = obj.get(key)?.asString?.trim() ?: ""
                    return if (v.equals("null", true) || v == "-") "" else v
                }

                var iccid = getVal("iccid").filter { it.isLetterOrDigit() }
                var phone = getVal("phoneNumber").filter { it.isDigit() }
                if (phone.startsWith("89") || (iccid.isNotBlank() && iccid.startsWith(phone))) {
                    if (iccid.length < 16 && phone.startsWith("89")) {
                        iccid = phone + iccid
                    }
                    phone = ""
                }

                results.add(
                    ParsedRecognitionResult(
                        objectType = type,
                        manufacturer = getVal("manufacturer").ifBlank { "Не определено" },
                        model = getVal("model").ifBlank { "Не определено" },
                        serialNumber = getVal("serialNumber").filter { it.isLetterOrDigit() },
                        macAddress = getVal("macAddress"),
                        imei = getVal("imei").filter { it.isDigit() },
                        phoneNumber = phone,
                        iccid = iccid,
                        vin = getVal("vin").filter { it.isLetterOrDigit() }.uppercase(),
                        vrn = getVal("vrn").replace(" ", "").uppercase(),
                        nkm = getVal("nkm").filter { it.isLetterOrDigit() }.uppercase(),
                        rawText = getVal("rawText").ifBlank { raw }
                    )
                )
            }
        } catch (e: Exception) {
            println("❌ Ошибка парсинга массива JSON: ${e.message}")
        }
        return results
    }

    private fun ensureAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return

        val requestBody = "scope=$SCOPE".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(OAUTH_URL)
            .addHeader("Authorization", "Basic $AUTH_CREDENTIALS")
            .addHeader("RqUID", UUID.randomUUID().toString())
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IllegalStateException("Auth error (${response.code}): $body")
            val json = gson.fromJson(body, JsonObject::class.java)
            accessToken = json.get("access_token").asString
            tokenExpiresAt = json.get("expires_at")?.asLong ?: (System.currentTimeMillis() + 1800_000)
        }
    }

    private fun uploadImage(file: File): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "general")
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(FILES_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                println("❌ Ошибка загрузки файла в GigaChat [${response.code}]: $body")
                return null
            }
            return gson.fromJson(body, JsonObject::class.java).get("id")?.asString
        }
    }

    private fun askGigaChat(fileId: String, prompt: String, modelName: String = "GigaChat-Max"): String {
        val root = JsonObject().apply {
            addProperty("model", modelName)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                    add("attachments", JsonArray().apply { add(fileId) })
                })
            })
            addProperty("temperature", 0.1)
            addProperty("max_tokens", 400)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""

            // Если токены кончились (402, 429) или модель недоступна, а мы были на Max:
            if (!response.isSuccessful) {
                println("⚠️ Ошибка GigaChat [$modelName] код ${response.code}: $body")

                if (modelName == "GigaChat-Max" && (response.code == 402 || response.code == 429 || body.contains("balance", true))) {
                    println("🔄 Токены на GigaChat-Max закончились! Автоматически переключаюсь на GigaChat-Ultra...")
                    return askGigaChat(fileId, prompt, modelName = "GigaChat-Ultra")
                }
                return ""
            }

            val choices = gson.fromJson(body, JsonObject::class.java).getAsJsonArray("choices")
            return choices?.get(0)?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString ?: ""
        }
    }

    private fun buildUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}