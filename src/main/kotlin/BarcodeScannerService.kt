package com.example

import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage

object BarcodeScannerService {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13
                )
            )
        )
    }

    fun scan(image: BufferedImage): String? {
        // 1. Попытка на полном кадре
        decode(image)?.let { return it }

        // 2. Попытка в центральной области (где шильдики ДУТ и терминалов)
        try {
            val cropW = (image.width * 0.7).toInt()
            val cropH = (image.height * 0.5).toInt()
            val startX = (image.width - cropW) / 2
            val startY = (image.height * 0.1).toInt()
            val centerCrop = image.getSubimage(startX, startY, cropW, cropH)
            decode(centerCrop)?.let { return it }
        } catch (_: Exception) {}

        // 3. Попытка в нижней половине (для SIM-карт)
        try {
            val halfHeight = image.height / 2
            val bottomHalf = image.getSubimage(0, halfHeight, image.width, halfHeight)
            decode(bottomHalf)?.let { return it }
        } catch (_: Exception) {}

        return null
    }

    private fun decode(img: BufferedImage): String? {
        val source = BufferedImageLuminanceSource(img)

        // Попытка 1: HybridBinarizer (стандарт)
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = reader.decodeWithState(bitmap).text.trim()
            reader.reset()
            if (text.isNotBlank()) return text
        } catch (_: Exception) {
            reader.reset()
        }

        // Попытка 2: GlobalHistogramBinarizer (лучше работает при перепадах света и тенях)
        try {
            val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
            val text = reader.decodeWithState(bitmap).text.trim()
            reader.reset()
            if (text.isNotBlank()) return text
        } catch (_: Exception) {
            reader.reset()
        }

        // Попытка 3: Инверсия цветов
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source.invert()))
            val text = reader.decodeWithState(bitmap).text.trim()
            reader.reset()
            if (text.isNotBlank()) return text
        } catch (_: Exception) {
            reader.reset()
        }

        return null
    }
}