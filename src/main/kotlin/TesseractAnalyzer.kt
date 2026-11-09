package com.example

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import net.sourceforge.tess4j.Tesseract
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO

class TesseractAnalyzer(tessDataPath: String = "C:/Program Files/Tesseract-OCR/tessdata") : ImageAnalyzer {

    private val tesseract = Tesseract().apply {
        setDatapath(tessDataPath)
        setLanguage("rus+eng")
        setOcrEngineMode(1)
    }

    // Регулярные выражения терминалов Навтелеком
    private val smartModelRegex = Regex("""SMART[\s\-_]*S[\s\-_]*(24\d{2})""", RegexOption.IGNORE_CASE)
    private val snModelRegex = Regex("""SN[\s:]*(24\d{2})\d{4,8}""", RegexOption.IGNORE_CASE)
    private val manufacturerRegex = Regex("""(Navtelecom|Навтелеком|Teltonika|Ruptela|Galileosky|Неоматика)""", RegexOption.IGNORE_CASE)

    // Поиск IMEI: штрихкод или метка в тексте
    private val imeiLabelRegex = Regex("""(?:IMEI|ИМЕЙ|ЕМЕЙ)[\s:]*([0-9\s]{15,20})""", RegexOption.IGNORE_CASE)
    private val strictImeiRegex = Regex("""\b\d{15}\b""")

    // Поиск данных SIM-карты (строгие правила)
    private val iccidRegex = Regex("""\b89\d{16,18}\b""")
    private val phoneWithPrefixRegex = Regex("""(?:\+7|8)[\s\-]?[9]\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}""")

    override fun analyze(imageFile: File): DeviceRecord {
        val now = LocalDateTime.now()
        val recordId = System.currentTimeMillis().toString().takeLast(6)

        val originalImage = ImageIO.read(imageFile) ?: throw RuntimeException("Не удалось открыть изображение")
        val barcodes = mutableSetOf<String>()
        val textCollector = StringBuilder()

        // Сканируем под 4 углами
        for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
            val img = if (angle == 0.0) originalImage else rotateImage(originalImage, angle)
            barcodes.addAll(scanBarcodes(img))

            // 1) Обычный прогон
            try {
                val ocr = tesseract.doOCR(img)
                if (ocr.isNotBlank()) textCollector.append(" ").append(ocr)
            } catch (_: Exception) {}

            // 2) Прогон с повышенным контрастом (специально для точечных серых номеров SIM)
            try {
                val contrastImg = preprocessForDotMatrix(img)
                val ocrContrast = tesseract.doOCR(contrastImg)
                if (ocrContrast.isNotBlank()) textCollector.append(" ").append(ocrContrast)
            } catch (_: Exception) {}
        }

        val fullText = (barcodes.joinToString(" ") + " " + textCollector.toString()).replace("\r", " ").trim()

        // 1. Поиск ICCID (штрихкод или текст)
        val iccidFromBarcode = barcodes.firstOrNull { it.startsWith("89") && it.length in 18..20 }
        val iccidRegexWithSpaces = Regex("""\b89[\d\s]{16,24}\b""")
        val iccidFromText = iccidRegexWithSpaces.find(fullText)?.let { match ->
            val cleanDigits = match.value.filter { it.isDigit() }
            if (cleanDigits.length in 18..20) cleanDigits else null
        }
        val finalIccid = iccidFromBarcode ?: iccidFromText

        // 2. Исключаем ICCID из строки для чистого поиска номера телефона
        var textWithoutIccid = fullText
        if (finalIccid != null) {
            textWithoutIccid = textWithoutIccid.replace(finalIccid, " ")
        }
        iccidRegexWithSpaces.findAll(fullText).forEach {
            textWithoutIccid = textWithoutIccid.replace(it.value, " ")
        }

        // 3. Поиск номера телефона
        val normalizedText = textWithoutIccid
            .replace("O", "0")
            .replace("o", "0")

        // Вариант 1: Поиск рядом со словами "номер / Ваш номер / тел"
        val labelRegex = Regex("""(?:номер|тел)[\s:]*([+\d\s\-]{10,25})""", RegexOption.IGNORE_CASE)
        val phoneFromLabel = labelRegex.find(normalizedText)?.let { match ->
            val digits = match.groupValues[1].filter { it.isDigit() }
            when {
                digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8")) -> "+7" + digits.substring(1)
                digits.length == 10 && digits.startsWith("9") -> "+7$digits"
                digits.length > 11 && (digits.startsWith("79") || digits.startsWith("89")) -> "+7" + digits.substring(1, 11)
                else -> null
            }
        }

        // Вариант 2: Ищем прямую комбинацию РФ-номера: +7 9xx... или 8 9xx... или просто 9xx xxx xx xx
        val rawPattern = Regex("""(?:\+?7|8)?[\s\-]*(9\d{2})[\s\-]?(\d{3})[\s\-]?(\d{2})[\s\-]?(\d{2})""")
        val phoneFromPattern = rawPattern.find(normalizedText)?.let { match ->
            val fullDigits = match.value.filter { it.isDigit() }
            if (fullDigits.length == 11) "+7" + fullDigits.substring(1)
            else if (fullDigits.length == 10) "+7$fullDigits"
            else null
        }

        val finalPhone = phoneFromLabel ?: phoneFromPattern

        // 4. Проверка условий SIM-карты
        val hasSimKeywords = fullText.contains("SIM", ignoreCase = true) ||
                fullText.contains("СИМ", ignoreCase = true) ||
                fullText.contains("МегаФон", ignoreCase = true) ||
                fullText.contains("Билайн", ignoreCase = true) ||
                fullText.contains("МТС", ignoreCase = true) ||
                fullText.contains("Теле2", ignoreCase = true) ||
                fullText.contains("Ваш номер", ignoreCase = true) ||
                fullText.contains("ICCID", ignoreCase = true)

        if (finalIccid != null || (hasSimKeywords && finalPhone != null) || fullText.contains("Ваш номер", ignoreCase = true)) {
            return DeviceRecord(
                id = recordId,
                timestamp = now,
                objectType = ObjectType.SIM_CARD,
                phoneNumber = finalPhone ?: "Не распознан",
                iccid = finalIccid ?: "Не распознан",
                rawText = fullText.take(200)
            )
        }

        // 5. Проверка терминала
        val imeiFromBarcode = barcodes.firstOrNull { it.length == 15 && it.all { c -> c.isDigit() } }
        val snFromBarcode = barcodes.firstOrNull { it.length in 10..14 && it.startsWith("24") && it.all { c -> c.isDigit() } }

        val imeiFromLabel = imeiLabelRegex.find(fullText)?.let { match ->
            val digits = match.groupValues[1].filter { it.isDigit() }
            if (digits.length >= 15) digits.take(15) else null
        }
        val imeiFromRaw = strictImeiRegex.find(fullText)?.value
        val finalImei = imeiFromBarcode ?: imeiFromLabel ?: imeiFromRaw ?: "-"

        val model: String? = snFromBarcode?.let { "SMART S-${it.take(4)}" }
            ?: smartModelRegex.find(fullText)?.let { "SMART S-${it.groupValues[1]}" }
            ?: snModelRegex.find(fullText)?.let { "SMART S-${it.groupValues[1]}" }

        val manufacturer = manufacturerRegex.find(fullText)?.value
            ?: if (model != null || finalImei != "-" || snFromBarcode != null) "Navtelecom" else "-"

        val isTerminal = model != null || finalImei != "-" || snFromBarcode != null || fullText.contains("Navtelecom", ignoreCase = true)

        if (isTerminal) {
            return DeviceRecord(
                id = recordId,
                timestamp = now,
                objectType = ObjectType.TERMINAL,
                manufacturer = manufacturer,
                model = model ?: "SMART S-24xx",
                imei = finalImei,
                rawText = fullText.take(200)
            )
        }

        // 5.5 Проверка ДУТ (ESCORT и подобные)
        tryDetectDut(fullText)?.let { return it }

        // 6. Неопределенный объект
        return DeviceRecord(
            id = recordId,
            timestamp = now,
            objectType = ObjectType.UNKNOWN,
            rawText = fullText.take(200)
        )
    }

    private fun rotateImage(src: BufferedImage, angleDegrees: Double): BufferedImage {
        val rads = Math.toRadians(angleDegrees)
        val w = src.width
        val h = src.height
        val newW = if (angleDegrees == 90.0 || angleDegrees == 270.0) h else w
        val newH = if (angleDegrees == 90.0 || angleDegrees == 270.0) w else h

        val rotated = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g2d = rotated.createGraphics()
        val at = AffineTransform().apply {
            translate((newW - w) / 2.0, (newH - h) / 2.0)
            rotate(rads, w / 2.0, h / 2.0)
        }
        g2d.drawRenderedImage(src, at)
        g2d.dispose()
        return rotated
    }

    private fun scanBarcodes(bufferedImage: BufferedImage): List<String> {
        val results = mutableListOf<String>()
        try {
            val source = BufferedImageLuminanceSource(bufferedImage)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.EAN_13
                )
            )
            val result = MultiFormatReader().apply { setHints(hints) }.decodeWithState(bitmap)
            if (result != null) results.add(result.text)
        } catch (_: Exception) {}
        return results
    }
    private fun preprocessForDotMatrix(src: BufferedImage): BufferedImage {
        val w = src.width
        val h = src.height
        val gray = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = gray.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()

        // Увеличиваем контраст: всё, что темнее светло-серого фона, делаем темнее
        val output = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val raster = gray.raster
        val outRaster = output.raster

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = raster.getSample(x, y, 0)
                // Растягиваем динамический диапазон для точечных серых чернил
                val enhanced = when {
                    pixel > 210 -> 255  // Белый фон отсекаем
                    pixel < 120 -> 0    // Темные штрихи делаем чисто черными
                    else -> ((pixel - 120) * (255.0 / 90.0)).toInt().coerceIn(0, 255)
                }
                outRaster.setSample(x, y, 0, enhanced)
            }
        }
        return output
    }

    // отдельный инстанс для ДУТ: латиница + режимы под гравировку
    private val tesseractDut = Tesseract().apply {
        setDatapath(tessDataPath)
        setLanguage("eng")
        setOcrEngineMode(1)
    }

    // маски ДУТ
    private val dutMacRegex = Regex(
        """([0-9OoIl]{2})[:.\-]([0-9OoIl]{2})[:.\-]([0-9OoIl]{2})[:.\-]([0-9OoIl]{2})[:.\-]([0-9OoIl]{2})[:.\-]([0-9OoIl]{2})"""
    )
    private val dutSerialRegex = Regex("""(?<![0-9OoIlA-Za-z])([0-9OoIl]{12})(?![0-9OoIl])""")
    private val dutModelRegex = Regex("""(ESCORT[\s\-_]*)?(TD[\s\-_]*BLE)""", RegexOption.IGNORE_CASE)

    private fun normalizeHex(s: String) = s
        .replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1')
        .replace(Regex("[.\\-]"), ":")

    private fun tryDetectDut(fullText: String): DeviceRecord? {
        val mac = dutMacRegex.find(fullText)?.value?.let(::normalizeHex)
        val serial = dutSerialRegex.find(fullText)?.groupValues?.get(1)?.let(::normalizeHex)
        val modelMatch = dutModelRegex.find(fullText)
        val escortMention = fullText.contains("escort", ignoreCase = true)

        val isDut = modelMatch != null || escortMention || (mac != null && serial != null)
        if (!isDut) return null

        return DeviceRecord(
            id = System.currentTimeMillis().toString().takeLast(6),
            timestamp = LocalDateTime.now(),
            objectType = ObjectType.DUT,
            manufacturer = "Escort",
            model = "ESCORT TD BLE",   // пока фиксировано, когда появятся другие ДУТ — возьмём из modelMatch
            imei = serial ?: "-",      // временно кладём серийник сюда, см. пункт про models.kt ниже
            rawText = fullText.take(200),
            dutMac = mac ?: "-"
        )
    }

}