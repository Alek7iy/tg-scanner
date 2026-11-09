package com.example

import org.apache.commons.io.FileUtils
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.PhotoSize
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SensorBot(
    private val token: String,
    private val username: String,
    private val analyzer: ImageAnalyzer
) : TelegramLongPollingBot(token) {

    private val userRecords = ConcurrentHashMap<String, MutableList<DeviceRecord>>()
    // Состояние ожидания ручного ввода: chatId -> состояние
    private val userStates = ConcurrentHashMap<String, UserInputState>()

    override fun getBotUsername(): String = username

    override fun onUpdateReceived(update: Update) {
        // 1. Обработка нажатий на инлайн-кнопки
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update)
            return
        }

        if (!update.hasMessage()) return
        val message = update.message
        val chatId = message.chatId.toString()

        // 2. Если пользователь находится в режиме ручного ввода значения
        if (userStates.containsKey(chatId) && message.hasText()) {
            handleManualInput(chatId, message.text.trim())
            return
        }

        // 3. Стандартная обработка команд и фото
        when {
            message.hasText() -> {
                when (message.text.trim()) {
                    "/start" -> sendText(chatId, "👋 Бот готов! Отправляйте фото терминалов или SIM-карт.\n\nКоманды:\n/report — выгрузить Excel\n/clear — очистить список")
                    "/report" -> sendExcelReport(chatId)
                    "/clear" -> {
                        userRecords[chatId]?.clear()
                        userStates.remove(chatId)
                        sendText(chatId, "Список собранных устройств очищен.")
                    }
                    else -> sendText(chatId, "Жду фото устройства или команду /report.")
                }
            }

            message.hasPhoto() -> {
                val photo: PhotoSize = message.photo.maxByOrNull { it.fileSize ?: 0 } ?: message.photo.last()
                handleIncomingImage(chatId, photo.fileId)
            }

            message.hasDocument() -> {
                val doc = message.document
                if (doc.mimeType?.startsWith("image/") == true) {
                    handleIncomingImage(chatId, doc.fileId)
                } else {
                    sendText(chatId, "Пожалуйста, отправляйте только изображения.")
                }
            }
        }
    }

    private fun handleIncomingImage(chatId: String, fileId: String) {
        sendText(chatId, "⏳ Скачиваю и анализирую фото...")

        try {
            val tgFile = execute(GetFile(fileId))
            val fileUrl = tgFile.getFileUrl(botToken)

            val downloadsDir = File("downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val localFile = File(downloadsDir, "${System.currentTimeMillis()}_device.jpg")
            downloadFileWithRetry(fileUrl, localFile)

            val record = analyzer.analyze(localFile)
            userRecords.computeIfAbsent(chatId) { mutableListOf() }.add(record)

            val total = userRecords[chatId]?.size ?: 0
            val cardText = formatRecordCard(record, total)

            // Отправляем карточку с кнопками редактирования
            val send = SendMessage(chatId, cardText).apply {
                enableMarkdown(true)
                replyMarkup = createCardKeyboard(record.id)
            }
            execute(send)

        } catch (e: Exception) {
            e.printStackTrace()
            sendText(chatId, "❌ Ошибка при обработке: ${e.message}")
        }
    }

    private fun handleCallbackQuery(update: Update) {
        val query = update.callbackQuery
        val chatId = query.message.chatId.toString()
        val data = query.data ?: return

        val parts = data.split(":")
        val action = parts[0]
        val recordId = parts.getOrNull(1) ?: return

        val record = userRecords[chatId]?.find { it.id == recordId }
        if (record == null) {
            sendText(chatId, "Запись не найдена или уже удалена.")
            return
        }

        when (action) {
            "edit_model" -> {
                userStates[chatId] = UserInputState(recordId, EditField.MODEL)
                sendText(chatId, "✏️ Введите правильную модель (например: `SMART S-2423`):")
            }
            "edit_id" -> {
                userStates[chatId] = UserInputState(recordId, EditField.IDENTIFIER)
                val hint = if (record.objectType == ObjectType.SIM_CARD) "ICCID или номер телефона" else "IMEI (15 цифр)"
                sendText(chatId, "✏️ Введите правильный $hint:")
            }
            "delete" -> {
                userRecords[chatId]?.remove(record)
                sendText(chatId, "🗑 Запись удалена из списка.")
            }
        }
    }

    private fun handleManualInput(chatId: String, input: String) {
        val state = userStates.remove(chatId) ?: return
        val record = userRecords[chatId]?.find { it.id == state.recordId }

        if (record == null) {
            sendText(chatId, "Не удалось найти запись для изменения.")
            return
        }

        when (state.field) {
            EditField.MODEL -> {
                record.model = input
                sendText(chatId, "✅ Модель изменена на: `$input`")
            }
            EditField.IDENTIFIER -> {
                if (record.objectType == ObjectType.SIM_CARD) {
                    if (input.startsWith("89") && input.length in 18..20) {
                        record.iccid = input
                    } else {
                        record.phoneNumber = input
                    }
                } else {
                    record.imei = input
                }
                sendText(chatId, "✅ Идентификатор изменен на: `$input`")
            }
            EditField.MANUFACTURER -> {
                record.manufacturer = input
                sendText(chatId, "✅ Производитель изменен на: `$input`")
            }
        }
    }

    private fun formatRecordCard(record: DeviceRecord, total: Int): String {
        val details = when (record.objectType) {
            ObjectType.TERMINAL -> """
                📦 *Определен терминал:*
                • Производитель: `${record.manufacturer}`
                • Модель: `${record.model}`
                • IMEI: `${record.imei}`
            """.trimIndent()

            ObjectType.SIM_CARD -> """
                💳 *Определена SIM-карта:*
                • Номер телефона: `${record.phoneNumber}`
                • ICCID: `${record.iccid}`
            """.trimIndent()

            ObjectType.UNKNOWN -> """
                ⚠️ *Не удалось точно классифицировать объект:*
                Текст: ${record.rawText.take(100)}
            """.trimIndent()
        }

        return "$details\n\n_Всего в списке: $total шт._"
    }

    private fun createCardKeyboard(recordId: String): InlineKeyboardMarkup {
        val markup = InlineKeyboardMarkup()
        val row1 = listOf(
            InlineKeyboardButton("✏️ Модель").apply { callbackData = "edit_model:$recordId" },
            InlineKeyboardButton("✏️ IMEI / ICCID").apply { callbackData = "edit_id:$recordId" }
        )
        val row2 = listOf(
            InlineKeyboardButton("🗑 Удалить эту запись").apply { callbackData = "delete:$recordId" }
        )
        markup.keyboard = listOf(row1, row2)
        return markup
    }

    private fun sendExcelReport(chatId: String) {
        val records = userRecords[chatId]
        if (records.isNullOrEmpty()) {
            sendText(chatId, "У вас пока нет сохраненных данных. Сначала отправьте хотя бы одно фото.")
            return
        }

        try {
            sendText(chatId, "📊 Формирую отчет...")
            val excelFile = ExcelReportService.generateReport(records)

            val sendDoc = SendDocument(chatId, InputFile(excelFile)).apply {
                caption = "Готово! Собрано записей: ${records.size}"
            }
            execute(sendDoc)

        } catch (e: Exception) {
            e.printStackTrace()
            sendText(chatId, "Ошибка при формировании отчета: ${e.message}")
        }
    }

    private fun sendText(chatId: String, text: String) {
        val keyboardMarkup = ReplyKeyboardMarkup().apply {
            resizeKeyboard = true
            keyboard = listOf(KeyboardRow().apply {
                add("/report")
                add("/clear")
            })
        }

        val send = SendMessage(chatId, text).apply {
            enableMarkdown(true)
            replyMarkup = keyboardMarkup
        }
        execute(send)
    }
    private fun downloadFileWithRetry(fileUrl: String, destination: File, maxRetries: Int = 3) {
        var attempts = 0
        while (attempts < maxRetries) {
            try {
                val connection = java.net.URL(fileUrl).openConnection().apply {
                    connectTimeout = 10000 // 10 секунд на подключение
                    readTimeout = 15000    // 15 секунд на скачивание
                }
                connection.getInputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return
            } catch (e: Exception) {
                attempts++
                if (attempts >= maxRetries) throw e
                Thread.sleep(1000)
            }
        }
    }
}
fun main() {
    val botToken = "9225363619:AAEDs3vJP0YUhF31NekqoL-SFUyHFha82MA"
    val botUsername = "naviline_scanner_bot"

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    val analyzer = TesseractAnalyzer("C:/Program Files/Tesseract-OCR/tessdata")

    val bot = SensorBot(
        token = botToken,
        username = botUsername,
        analyzer = analyzer
    )

    botsApi.registerBot(bot)
    println("Бот запущен и готов к приему фото!")
}