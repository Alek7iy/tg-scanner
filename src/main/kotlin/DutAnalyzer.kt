package com.example

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage

data class DutInfo(val model: String?, val serial: String?, val mac: String?) {
    fun isComplete() = model != null && serial != null && mac != null
}

object DutAnalyzer {

    private val macRegex = Regex("([0-9OoIl]{2})[:.]([0-9OoIl]{2})[:.]([0-9OoIl]{2})[:.]([0-9OoIl]{2})[:.]([0-9OoIl]{2})[:.]([0-9OoIl]{2})")
    private val serialRegex = Regex("(?<![0-9OoIlA-Za-z])([0-9OoIl]{12})(?![0-9OoIl])")

    private fun normalize(s: String) = s.replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1').replace('.', ':')

    fun analyze(image: BufferedImage, tess: Tesseract): DutInfo {
        var model: String? = null
        var serial: String? = null
        var mac: String? = null

        // 0) QR — не данные, а ссылка на паспорт, но упоминание escort в ней подтверждает модель
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
            val qrText = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
            if (qrText.contains("escort", ignoreCase = true)) model = "ESCORT TD BLE"
        } catch (_: Exception) { /* QR не читается — это норма, идём в OCR */ }

        // 1) OCR в два прохода: цельный блок и «россыпь» строк
        val prepared = scaleAndGrayscale(image)
        for (psm in intArrayOf(6, 11)) {
            val text = runOcr(tess, prepared, psm)
            if (serial == null) serial = serialRegex.find(text)?.groupValues?.get(1)?.let(::normalize)
            if (mac == null) mac = macRegex.find(text)?.value?.let(::normalize)
            if (model == null && text.contains("escort", ignoreCase = true) &&
                text.contains("ble", ignoreCase = true)) model = "ESCORT TD BLE"
        }
        return DutInfo(model, serial, mac)
    }

    private fun runOcr(tess: Tesseract, img: BufferedImage, psm: Int): String = try {
        tess.setLanguage("eng")
        tess.setPageSegMode(psm)
        tess.doOCR(img)
    } catch (_: Exception) { "" }

    private fun scaleAndGrayscale(src: BufferedImage): BufferedImage {
        val w = src.width * 3
        val h = src.height * 3
        val gray = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = gray.createGraphics()
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return gray
    }

    fun formatReply(info: DutInfo): String {
        val lines = buildString {
            info.model?.let { appendLine("Модель: $it") }
            info.serial?.let { appendLine("Серийный номер: $it") }
            info.mac?.let { appendLine("MAC: $it") }
        }
        val missing = listOfNotNull(
            info.model?.let { null } ?: "модель",
            info.serial ?: "серийный номер",
            info.mac ?: "MAC"
        ).filter { it.isNotEmpty() }
        return if (missing.isEmpty()) "✅ ДУТ распознан:\n$lines"
        else "Распознал частично:\n$lines\nНе удалось прочитать: ${missing.joinToString(", ")}. Переснимите этикетку ближе, без бликов и по возможности строго спереди."
    }
}
