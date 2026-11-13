package com.example

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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.listOf

class SensorBot(
    token: String,
    private val username: String
) : TelegramLongPollingBot(token) {

    private val userRecords = ConcurrentHashMap<String, MutableList<DeviceRecord>>()
    private val userStates = ConcurrentHashMap<String, UserInputState>()

    // Буфер для накопления фото перед локальной обработкой пачкой
    private val photoBatchBuffer = ConcurrentHashMap<String, MutableList<File>>()
    private val MAX_PHOTOS_PER_BATCH = 6

    // Буфер накопления сложных фото для распознавания через ИИ
    private val aiFallbackQueue = ConcurrentHashMap<String, MutableList<File>>()
    private val AI_BATCH_TRIGGER_SIZE = 6

    override fun getBotUsername(): String = username

    override fun onUpdateReceived(update: Update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update)
            return
        }

        if (!update.hasMessage()) return
        val message = update.message
        val chatId = message.chatId.toString()

        // 1. Ручной ввод для редактирования полей карточки
        if (userStates.containsKey(chatId) && message.hasText()) {
            handleManualInput(chatId, message.text.trim())
            return
        }

        // 2. Обработка команд и кнопок меню
        if (message.hasText()) {
            val text = message.text.trim()
            when {
                text == "/start" -> {
                    sendReply(
                        chatId,
                        "👋 <b>Бот готов к работе!</b>\n\n" +
                                "Присылайте фото оборудования по одному или сразу пачкой (до $MAX_PHOTOS_PER_BATCH шт.).\n" +
                                "Как закончите загрузку — нажмите <b>«⚡ Распознать»</b>.\n\n" +
                                "Если фото окажется сложным или размытым, бот сохранит его в буфер ИИ " +
                                "и отправит в нейросеть пачкой по $AI_BATCH_TRIGGER_SIZE шт. (или по кнопке)."
                    )
                }
                text.startsWith("⚡ Распознать") -> {
                    processBatch(chatId)
                }
                text == "❌ Сбросить очередь" -> {
                    photoBatchBuffer.remove(chatId)?.forEach { it.delete() }
                    sendReply(chatId, "🗑 Очередь загруженных фото очищена.")
                }
                text.startsWith("🤖 Распознать через ИИ") -> {
                    processAiBatch(chatId)
                }
                text == "📄 Отчет в Excel" || text == "/report" -> {
                    sendExcelReport(chatId)
                }
                text == "🗑 Очистить базу" || text == "/clear" -> {
                    userRecords[chatId]?.clear()
                    userStates.remove(chatId)
                    aiFallbackQueue.remove(chatId)?.forEach { it.delete() }
                    sendReply(chatId, "База сохраненных устройств и буфер ИИ очищены.")
                }
                else -> {
                    sendReply(chatId, "Жду фото оборудования или команду из меню.")
                }
            }
            return
        }

        // 3. Обработка входящих изображений
        if (message.hasPhoto()) {
            val photo: PhotoSize = message.photo.maxByOrNull { it.fileSize ?: 0 } ?: message.photo.last()
            queueIncomingPhoto(chatId, photo.fileId)
            return
        }

        if (message.hasDocument()) {
            val doc = message.document
            if (doc.mimeType?.startsWith("image/") == true) {
                queueIncomingPhoto(chatId, doc.fileId)
            } else {
                sendReply(chatId, "Пожалуйста, отправляйте только изображения (JPG, PNG).")
            }
            return
        }
    }

    private fun queueIncomingPhoto(chatId: String, fileId: String) {
        val queue = photoBatchBuffer.computeIfAbsent(chatId) { mutableListOf() }

        if (queue.size >= MAX_PHOTOS_PER_BATCH) {
            sendReply(chatId, "⚠️ В очереди уже максимум ($MAX_PHOTOS_PER_BATCH фото). Нажмите кнопку <b>«⚡ Распознать»</b>!")
            return
        }

        try {
            val tgFile = execute(GetFile(fileId))
            val fileUrl = tgFile.getFileUrl(botToken)

            val downloadsDir = File("downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val localFile = File(downloadsDir, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
            downloadFileWithRetry(fileUrl, localFile)

            queue.add(localFile)
            val count = queue.size

            if (count == MAX_PHOTOS_PER_BATCH) {
                sendReply(chatId, "✅ Набрано $MAX_PHOTOS_PER_BATCH фото! Начинаю локальную обработку...")
                processBatch(chatId)
            } else {
                sendReply(
                    chatId,
                    "📸 Фото принято в очередь (<b>$count из $MAX_PHOTOS_PER_BATCH</b>).\n" +
                            "Отправьте ещё или нажмите <b>«⚡ Распознать ($count из $MAX_PHOTOS_PER_BATCH)»</b>."
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendReply(chatId, "❌ Ошибка при загрузке фото: ${e.message}")
        }
    }

    private fun processBatch(chatId: String) {
        val photos = photoBatchBuffer.remove(chatId) ?: mutableListOf()
        if (photos.isEmpty()) {
            sendReply(chatId, "В очереди нет фото. Сначала отправьте хотя бы один снимок.")
            return
        }

        sendReply(chatId, "⏳ Обрабатываю <b>${photos.size}</b> фото локальными движками (RapidOCR + Tesseract)...")

        Thread {
            try {
                for (photoFile in photos) {
                    processSinglePhoto(chatId, photoFile)
                    if (photoFile.exists()) photoFile.delete()
                }

                sendReply(chatId, "✅ Локальная обработка завершена.")
            } catch (e: Exception) {
                e.printStackTrace()
                sendReply(chatId, "❌ Ошибка при обработке пакета: ${e.message}")
            }
        }.start()
    }

    private fun processSinglePhoto(chatId: String, photoFile: File) {
        // 1. Быстрый перехват штрихкодов (ZXing)
        val barcode = try {
            val image = javax.imageio.ImageIO.read(photoFile)
            if (image != null) BarcodeScannerService.scan(image) else null
        } catch (_: Exception) {
            null
        }

        // 2. ЭТАП 1: RapidOCR (Нейросетевой локальный OCR)
        val rapidText = RapidOcrService.extractText(photoFile)
        val rapidResult = if (rapidText.isNotBlank()) {
            OcrTextParser.tryParseFromOcr(rapidText, rapidText, barcode)
        } else null

        if (isCompleteResult(rapidResult)) {
            saveAndSendCard(chatId, rapidResult!!)
            return
        }

        // 3. ЭТАП 2: Tesseract OCR (Резервный локальный OCR)
        val (hardwareText, fullText) = TesseractOcrService.extractTextDual(photoFile)

        val combinedHardware = "$rapidText\n$hardwareText".trim()
        val combinedFull = "$rapidText\n$fullText".trim()

        val finalLocalResult = OcrTextParser.tryParseFromOcr(combinedHardware, combinedFull, barcode)

        if (isCompleteResult(finalLocalResult)) {
            saveAndSendCard(chatId, finalLocalResult!!)
        } else {
            // 4. ЭТАП 3: Если оба локальных движка не справились — в буфер GigaChat Vision
            queuePhotoForAi(chatId, photoFile)
        }
    }

    private fun isCompleteResult(result: ParsedRecognitionResult?): Boolean {
        if (result == null || result.objectType == ObjectType.UNKNOWN) return false
        return when (result.objectType) {
            ObjectType.TERMINAL -> result.imei.isNotBlank() || result.serialNumber.isNotBlank()
            ObjectType.DUT -> result.macAddress.isNotBlank() || result.serialNumber.isNotBlank()
            ObjectType.TACHOGRAPH_PLATE -> result.vin.isNotBlank() || result.nkm.isNotBlank() || result.serialNumber.isNotBlank()
            ObjectType.SIM_CARD -> result.iccid.isNotBlank() || result.phoneNumber.isNotBlank()
            ObjectType.CAMERA -> result.serialNumber.isNotBlank()
            ObjectType.VEHICLE_PLATE -> result.vrn.isNotBlank()
            ObjectType.TACHOGRAPH_RECEIPT -> result.serialNumber.isNotBlank() || result.vin.isNotBlank()
            else -> false
        }
    }

    private fun queuePhotoForAi(chatId: String, photoFile: File) {
        val queue = aiFallbackQueue.computeIfAbsent(chatId) { mutableListOf() }

        val aiDir = File("ai_queue").apply { if (!exists()) mkdirs() }
        val queuedFile = File(aiDir, "ai_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        photoFile.copyTo(queuedFile, overwrite = true)
        queue.add(queuedFile)

        val count = queue.size

        if (count >= AI_BATCH_TRIGGER_SIZE) {
            sendReply(chatId, "⚡ <b>Набралось $AI_BATCH_TRIGGER_SIZE нераспознанных фото!</b>\nОтправляю пакет в нейросеть...")
            processAiBatch(chatId)
        } else {
            val markup = InlineKeyboardMarkup().apply {
                keyboard = listOf(
                    listOf(
                        InlineKeyboardButton("🤖 Распознать через ИИ ($count из $AI_BATCH_TRIGGER_SIZE)").apply {
                            callbackData = "trigger_ai_batch"
                        }
                    )
                )
            }
            val msg = SendMessage(
                chatId,
                "⚠️ Не удалось точно прочитать данные локально.\n" +
                        "Фото отложено в буфер для нейросети (<b>$count из $AI_BATCH_TRIGGER_SIZE</b>).\n" +
                        "Оно отправится автоматически при наборе $AI_BATCH_TRIGGER_SIZE шт., либо вы можете запустить анализ прямо сейчас кнопкой ниже:"
            ).apply {
                enableHtml(true)
                replyMarkup = markup
            }
            execute(msg)
        }
    }

    private fun processAiBatch(chatId: String) {
        val photos = aiFallbackQueue.remove(chatId) ?: mutableListOf()
        if (photos.isEmpty()) {
            sendReply(chatId, "Буфер для ИИ пуст. Нет отложенных фотографий.")
            return
        }

        sendReply(chatId, "🤖 Отправляю <b>${photos.size}</b> фото в Vision ИИ для глубокого распознавания...")

        Thread {
            try {
                val results = mutableListOf<ParsedRecognitionResult>()
                for (photo in photos) {
                    try {
                        val res = GigaChatVisionService.analyzeDevicePhoto(photo)
                        if (res.objectType != ObjectType.UNKNOWN) {
                            results.add(res)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                for (res in results) {
                    saveAndSendCard(chatId, res)
                }

                photos.forEach { if (it.exists()) it.delete() }
                sendReply(chatId, "✅ <b>Нейросеть завершила анализ!</b> Добавлено устройств: ${results.size}.")
            } catch (e: Exception) {
                e.printStackTrace()
                sendReply(chatId, "❌ Ошибка при обращении к ИИ: ${e.message}\nФотографии возвращены в буфер.")
                aiFallbackQueue.computeIfAbsent(chatId) { mutableListOf() }.addAll(photos)
            }
        }.start()
    }

    private fun saveAndSendCard(chatId: String, result: ParsedRecognitionResult) {
        val records = userRecords.computeIfAbsent(chatId) { mutableListOf() }
        val now = System.currentTimeMillis()
        val mergeWindowMs = 2 * 60 * 1000L

        if (result.objectType == ObjectType.TACHOGRAPH_RECEIPT) {
            val recentIndex = records.indexOfLast {
                it.objectType == ObjectType.TACHOGRAPH_RECEIPT && (now - it.createdAt) <= mergeWindowMs
            }

            if (recentIndex != -1) {
                val merged = records[recentIndex].mergeReceipt(result)
                records[recentIndex] = merged

                val cardText = formatRecordCard(merged, records.size)
                val msgId = merged.messageId
                if (msgId != null) {
                    try {
                        val editMsg = EditMessageText().apply {
                            this.chatId = chatId
                            this.messageId = msgId
                            this.text = cardText
                            enableHtml(true)
                            replyMarkup = createCardKeyboard(merged)
                        }
                        execute(editMsg)
                        return
                    } catch (_: Exception) {}
                }

                val sendUpdated = SendMessage(chatId, "✅ <b>Чек дополнен:</b>\n\n$cardText").apply {
                    enableHtml(true)
                    replyMarkup = createCardKeyboard(merged)
                }
                val sentMsg = execute(sendUpdated)
                merged.messageId = sentMsg.messageId
                return
            }
        }

        val record = DeviceRecord(
            id = UUID.randomUUID().toString(),
            timestamp = LocalDateTime.now(),
            objectType = result.objectType,
            manufacturer = result.manufacturer,
            model = result.model,
            serialNumber = result.serialNumber.ifBlank { "НЕ_РАСПОЗНАН" },
            macAddress = result.macAddress.ifBlank { "НЕ_РАСПОЗНАН" },
            imei = result.imei.ifBlank { "НЕ_РАСПОЗНАН" },
            phoneNumber = result.phoneNumber,
            iccid = result.iccid,
            vin = result.vin.ifBlank { "НЕ_РАСПОЗНАН" },
            vrn = result.vrn.ifBlank { "НЕ_РАСПОЗНАН" },
            nkm = result.nkm.ifBlank { "НЕ_РАСПОЗНАН" },
            rawText = result.rawText,
            nkmExpiry = result.nkmExpiry.ifBlank { "НЕ_РАСПОЗНАН" },
            mileage = result.mileage.ifBlank { "НЕ_РАСПОЗНАН" },
            calibrationInfo = result.calibrationInfo.ifBlank { "НЕ_РАСПОЗНАН" },
            canInfo = result.canInfo.ifBlank { "НЕ_РАСПОЗНАН" }
        )

        records.add(record)

        val cardText = formatRecordCard(record, records.size)
        val send = SendMessage(chatId, cardText).apply {
            enableHtml(true)
            replyMarkup = createCardKeyboard(record)
        }
        val sentMsg = execute(send)
        record.messageId = sentMsg.messageId
    }

    private fun handleCallbackQuery(update: Update) {
        val query = update.callbackQuery
        val chatId = query.message.chatId.toString()
        val cardMessageId = query.message.messageId
        val data = query.data ?: return

        if (data == "trigger_ai_batch") {
            processAiBatch(chatId)
            return
        }

        val parts = data.split(":")
        val action = parts[0]
        val recordId = parts.getOrNull(1) ?: return

        val record = userRecords[chatId]?.find { it.id == recordId }
        if (record == null) {
            sendReply(chatId, "Запись не найдена или уже удалена.")
            return
        }

        fun sendCopyPrompt(title: String, currentValue: String?, hintExample: String) {
            val cleanVal = currentValue?.trim() ?: ""
            val prompt = if (cleanVal.isNotBlank() && cleanVal != "НЕ_РАСПОЗНАН") {
                """
                ✏️ <b>$title</b>

                Текущее значение (нажмите для копирования):
                <code>$cleanVal</code>

                <i>Вставьте в поле ввода, исправьте символы и отправьте ответ.</i>
                """.trimIndent()
            } else {
                "✏️ Введите $title (например: <code>$hintExample</code>):"
            }
            sendReply(chatId, prompt)
        }

        when (action) {
            "edit_model" -> {
                userStates[chatId] = UserInputState(recordId, EditField.MODEL, cardMessageId)
                sendCopyPrompt("Редактирование модели", record.model, "СМАРТ S-2423")
            }
            "edit_id" -> {
                userStates[chatId] = UserInputState(recordId, EditField.IDENTIFIER, cardMessageId)
                if (record.objectType == ObjectType.SIM_CARD) {
                    val simVal = record.iccid?.ifBlank { record.phoneNumber } ?: ""
                    sendCopyPrompt("Редактирование ICCID / Номера", simVal, "8970120... или +7900...")
                } else {
                    sendCopyPrompt("Редактирование IMEI (15 цифр)", record.imei, "862059060160028")
                }
            }
            "edit_dut_sn" -> {
                userStates[chatId] = UserInputState(recordId, EditField.DUT_SERIAL, cardMessageId)
                sendCopyPrompt("Редактирование серийного номера (S/N)", record.serialNumber, "260963156063")
            }
            "edit_dut_mac" -> {
                userStates[chatId] = UserInputState(recordId, EditField.DUT_MAC, cardMessageId)
                sendCopyPrompt("Редактирование MAC-адреса", record.macAddress, "D9:D5:68:89:9A:9C")
            }
            "edit_vin" -> {
                userStates[chatId] = UserInputState(recordId, EditField.VIN, cardMessageId)
                sendCopyPrompt("Редактирование VIN (17 символов)", record.vin, "X894680B1R0GD6503")
            }
            "edit_vrn" -> {
                userStates[chatId] = UserInputState(recordId, EditField.VRN, cardMessageId)
                sendCopyPrompt("Редактирование госномера (VRN)", record.vrn, "E337KH156")
            }
            "edit_nkm" -> {
                userStates[chatId] = UserInputState(recordId, EditField.NKM, cardMessageId)
                sendCopyPrompt("Редактирование номера НКМ (СКЗИ)", record.nkm, "26G3100000231823")
            }
            "edit_workshop" -> {
                userStates[chatId] = UserInputState(recordId, EditField.WORKSHOP, cardMessageId)
                sendCopyPrompt("Редактирование мастерской", record.manufacturer, "ООО НАВИТЕХ")
            }
            "edit_tacho_model" -> {
                userStates[chatId] = UserInputState(recordId, EditField.TACHO_MODEL, cardMessageId)
                sendCopyPrompt("Редактирование модели тахографа", record.model, "Атол Drive Smart")
            }
            "delete" -> {
                userRecords[chatId]?.remove(record)
                sendReply(chatId, "🗑 Запись удалена из списка.")
            }
        }
    }

    private fun handleManualInput(chatId: String, input: String) {
        val state = userStates.remove(chatId) ?: return
        val record = userRecords[chatId]?.find { it.id == state.recordId }

        if (record == null) {
            sendReply(chatId, "Не удалось найти запись для изменения.")
            return
        }

        val raw = input.trim()

        when (state.field) {
            EditField.MODEL -> {
                record.model = raw
                sendReply(chatId, "✅ Модель изменена на: <code>$raw</code>")
            }
            EditField.IDENTIFIER -> {
                if (record.objectType == ObjectType.SIM_CARD) {
                    val digits = raw.filter { it.isDigit() }
                    if (digits.startsWith("89") && digits.length in 18..20) {
                        record.iccid = digits
                        sendReply(chatId, "✅ ICCID изменен на: <code>$digits</code>")
                    } else {
                        record.phoneNumber = raw
                        sendReply(chatId, "✅ Номер телефона изменен на: <code>$raw</code>")
                    }
                } else {
                    val digits = raw.filter { it.isDigit() }
                    record.imei = digits
                    sendReply(chatId, "✅ IMEI изменен на: <code>$digits</code>")
                }
            }
            EditField.MANUFACTURER -> {
                record.manufacturer = raw
                sendReply(chatId, "✅ Производитель изменен на: <code>$raw</code>")
            }
            EditField.DUT_SERIAL -> {
                record.serialNumber = raw
                sendReply(chatId, "✅ S/N изменен на: <code>$raw</code>")
            }
            EditField.DUT_MAC -> {
                val upper = raw.uppercase()
                val hexOnly = upper.replace(Regex("""[^0-9A-F]"""), "")
                val formattedMac = if (hexOnly.length == 12 && !upper.contains(":")) {
                    hexOnly.chunked(2).joinToString(":")
                } else {
                    upper
                }
                record.macAddress = formattedMac
                sendReply(chatId, "✅ MAC-адрес изменен на: <code>$formattedMac</code>")
            }
            EditField.VIN -> {
                val translitVin = raw.uppercase().map { char ->
                    when (char) {
                        'А' -> 'A'; 'В' -> 'B'; 'Е' -> 'E'; 'К' -> 'K'; 'М' -> 'M'
                        'Н' -> 'H'; 'О' -> 'O'; 'Р' -> 'P'; 'С' -> 'C'; 'Т' -> 'T'; 'Х' -> 'X'
                        else -> char
                    }
                }.joinToString("").replace(Regex("""\s+"""), "")
                record.vin = translitVin
                sendReply(chatId, "✅ VIN изменен на: <code>$translitVin</code>")
            }
            EditField.VRN -> {
                val vrn = raw.uppercase().replace(Regex("""\s+"""), "")
                record.vrn = vrn
                sendReply(chatId, "✅ Госномер изменен на: <code>$vrn</code>")
            }
            EditField.NKM -> {
                val nkm = raw.uppercase().replace(Regex("""\s+"""), "")
                record.nkm = nkm
                sendReply(chatId, "✅ НКМ (СКЗИ) изменен на: <code>$nkm</code>")
            }
            EditField.WORKSHOP -> {
                record.manufacturer = raw
                sendReply(chatId, "✅ Мастерская изменена на: <code>$raw</code>")
            }
            EditField.TACHO_MODEL -> {
                record.model = raw
                sendReply(chatId, "✅ Модель тахографа изменена на: <code>$raw</code>")
            }
        }

        // Авто-обновление карточки в чате
        val msgId = state.cardMessageId ?: record.messageId
        if (msgId != null) {
            try {
                val total = userRecords[chatId]?.size ?: 1
                val updatedText = formatRecordCard(record, total)
                val editMsg = EditMessageText().apply {
                    this.chatId = chatId
                    this.messageId = msgId
                    this.text = updatedText
                    enableHtml(true)
                    replyMarkup = createCardKeyboard(record)
                }
                execute(editMsg)
            } catch (_: Exception) {}
        }
    }

    private fun formatRecordCard(record: DeviceRecord, total: Int): String {
        val details = when (record.objectType) {
            ObjectType.TERMINAL -> """
                 📦 <b>Определен терминал:</b>
                 • Производитель: <code>${escapeHtml(record.manufacturer)}</code>
                 • Модель: <code>${escapeHtml(record.model)}</code>
                 • S/N: <code>${escapeHtml(record.serialNumber)}</code>
                 • IMEI: <code>${escapeHtml(record.imei)}</code>
            """.trimIndent()

            ObjectType.SIM_CARD -> """
                💳 <b>Определена SIM-карта:</b>
                • Номер телефона: <code>${escapeHtml(record.phoneNumber ?: "НЕ_УКАЗАН")}</code>
                • ICCID: <code>${escapeHtml(record.iccid ?: "НЕ_РАСПОЗНАН")}</code>
            """.trimIndent()

            ObjectType.DUT -> """
                ⛽ <b>Определен ДУТ:</b>
                • Модель: <code>${escapeHtml(record.model)}</code>
                • S/N: <code>${escapeHtml(record.serialNumber)}</code>
                • MAC: <code>${escapeHtml(record.macAddress)}</code>
            """.trimIndent()

            ObjectType.TACHOGRAPH_PLATE -> """
                🚚 <b>Табличка калибровки (ТС / Тахограф):</b>
                • Мастерская: <code>${escapeHtml(record.manufacturer)}</code>
                • VIN: <code>${escapeHtml(record.vin)}</code>
                • Госномер (VRN): <code>${escapeHtml(record.vrn)}</code>
                • Тахограф: <code>${escapeHtml(record.model)}</code>
                • C/N (S/N): <code>${escapeHtml(record.serialNumber)}</code>
                • НКМ (СКЗИ): <code>${escapeHtml(record.nkm)}</code>
            """.trimIndent()

            ObjectType.CAMERA -> """
                📷 <b>Определена камера видеонаблюдения:</b>
                • Производитель: <code>${escapeHtml(record.manufacturer)}</code>
                • Модель: <code>${escapeHtml(record.model)}</code>
                • S/N: <code>${escapeHtml(record.serialNumber)}</code>
            """.trimIndent()

            ObjectType.TACHOGRAPH_RECEIPT -> """
                🧾 <b>Чек тахографа (Техданные):</b>
                • Мастерская: <code>${escapeHtml(record.manufacturer)}</code>
                • Тахограф: <code>${escapeHtml(record.model)}</code> (S/N: <code>${escapeHtml(record.serialNumber)}</code>)
                • ТС / VIN: <code>${escapeHtml(record.vrn)}</code> / <code>${escapeHtml(record.vin)}</code>
                • Пробег: <code>${escapeHtml(record.mileage)} км</code>
                • НКМ (СКЗИ): <code>${escapeHtml(record.nkm)}</code> (до <code>${escapeHtml(record.nkmExpiry)}</code>)
                • Калибровка: <code>${escapeHtml(record.calibrationInfo)}</code>
                • CAN1: <code>${escapeHtml(record.canInfo)}</code>
            """.trimIndent()

            ObjectType.UNKNOWN -> """
                ⚠️ <b>Не удалось точно классифицировать объект:</b>
                Текст: ${escapeHtml(record.rawText.take(120))}
            """.trimIndent()

            ObjectType.VEHICLE_PLATE -> """
            🚘 <b>Определен госномер:</b>
            • Тип: <code>${escapeHtml(record.model)}</code>
            • Номер: <code>${escapeHtml(record.vrn)}</code>
        """.trimIndent()


        }

        return "$details\n\n<i>Всего в списке: $total шт.</i>"
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun createCardKeyboard(record: DeviceRecord): InlineKeyboardMarkup {
        val markup = InlineKeyboardMarkup()
        val rows = mutableListOf<List<InlineKeyboardButton>>()

        when (record.objectType) {
            ObjectType.DUT -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ S/N").apply { callbackData = "edit_dut_sn:${record.id}" },
                        InlineKeyboardButton("✏️ MAC").apply { callbackData = "edit_dut_mac:${record.id}" }
                    )
                )
            }
            ObjectType.SIM_CARD -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ Модель").apply { callbackData = "edit_model:${record.id}" },
                        InlineKeyboardButton("✏️ Номер/ICCID").apply { callbackData = "edit_id:${record.id}" }
                    )
                )
            }
            ObjectType.CAMERA -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ Модель").apply { callbackData = "edit_model:${record.id}" },
                        InlineKeyboardButton("✏️ S/N").apply { callbackData = "edit_dut_sn:${record.id}" }
                    )
                )
            }
            ObjectType.TERMINAL -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ S/N").apply { callbackData = "edit_dut_sn:${record.id}" },
                        InlineKeyboardButton("✏️ IMEI").apply { callbackData = "edit_id:${record.id}" }
                    )
                )
            }
            ObjectType.TACHOGRAPH_PLATE, ObjectType.TACHOGRAPH_RECEIPT -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ Мастерская").apply { callbackData = "edit_workshop:${record.id}" },
                        InlineKeyboardButton("✏️ Тахограф").apply { callbackData = "edit_tacho_model:${record.id}" }
                    )
                )
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ VIN").apply { callbackData = "edit_vin:${record.id}" },
                        InlineKeyboardButton("✏️ Госномер").apply { callbackData = "edit_vrn:${record.id}" }
                    )
                )
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ C/N (Taxo)").apply { callbackData = "edit_dut_sn:${record.id}" },
                        InlineKeyboardButton("✏️ НКМ").apply { callbackData = "edit_nkm:${record.id}" }
                    )
                )
            }
            ObjectType.VEHICLE_PLATE -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ Госномер").apply { callbackData = "edit_vrn:${record.id}" }
                    )
                )
            }
            ObjectType.UNKNOWN -> {
                rows.add(
                    listOf(
                        InlineKeyboardButton("✏️ Модель").apply { callbackData = "edit_model:${record.id}" },
                        InlineKeyboardButton("✏️ ID/SN").apply { callbackData = "edit_id:${record.id}" }
                    )
                )
            }
        }

        rows.add(
            listOf(
                InlineKeyboardButton("🗑 Удалить эту запись").apply { callbackData = "delete:${record.id}" }
            )
        )

        markup.keyboard = rows
        return markup
    }

    private fun sendExcelReport(chatId: String) {
        val records = userRecords[chatId]
        if (records.isNullOrEmpty()) {
            sendReply(chatId, "У вас пока нет сохраненных данных. Сначала отправьте хотя бы одно фото.")
            return
        }

        try {
            sendReply(chatId, "📊 Формирую отчет...")
            val excelFile = ExcelReportService.generateReport(records)

            val sendDoc = SendDocument(chatId, InputFile(excelFile)).apply {
                caption = "Готово! Собрано записей: ${records.size}"
            }
            execute(sendDoc)

        } catch (e: Exception) {
            e.printStackTrace()
            sendReply(chatId, "Ошибка при формировании отчета: ${e.message}")
        }
    }

    private fun sendReply(chatId: String, textHtml: String) {
        val send = SendMessage(chatId, textHtml).apply {
            enableHtml(true)
            replyMarkup = getMainMenuKeyboard(chatId)
        }
        execute(send)
    }

    private fun getMainMenuKeyboard(chatId: String): ReplyKeyboardMarkup {
        val keyboard = ReplyKeyboardMarkup()
        keyboard.resizeKeyboard = true
        keyboard.selective = true

        val queueSize = photoBatchBuffer[chatId]?.size ?: 0
        val aiQueueSize = aiFallbackQueue[chatId]?.size ?: 0
        val rows = mutableListOf<KeyboardRow>()

        if (queueSize > 0) {
            val actionRow = KeyboardRow()
            actionRow.add(KeyboardButton("⚡ Распознать ($queueSize из $MAX_PHOTOS_PER_BATCH)"))
            actionRow.add(KeyboardButton("❌ Сбросить очередь"))
            rows.add(actionRow)
        }

        if (aiQueueSize > 0) {
            val aiRow = KeyboardRow()
            aiRow.add(KeyboardButton("🤖 Распознать через ИИ ($aiQueueSize из $AI_BATCH_TRIGGER_SIZE)"))
            rows.add(aiRow)
        }

        val reportRow = KeyboardRow()
        reportRow.add(KeyboardButton("📄 Отчет в Excel"))
        reportRow.add(KeyboardButton("🗑 Очистить базу"))
        rows.add(reportRow)

        keyboard.keyboard = rows
        return keyboard
    }

    private fun downloadFileWithRetry(fileUrl: String, destination: File, maxRetries: Int = 3) {
        var attempts = 0
        while (attempts < maxRetries) {
            try {
                val connection = java.net.URL(fileUrl).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 15000
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

    // Запускаем фоновый Python-сервер RapidOCR (если он настроен в проекте)
    RapidOcrService.startServer()

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)

    val bot = SensorBot(
        token = botToken,
        username = botUsername
    )

    botsApi.registerBot(bot)
    println("Бот запущен и готов к приему фото!")
}