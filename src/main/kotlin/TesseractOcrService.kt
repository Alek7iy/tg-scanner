package com.example

import net.sourceforge.tess4j.Tesseract
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.awt.image.RescaleOp
import java.io.File
import javax.imageio.ImageIO

object TesseractOcrService {

    // 1. Профиль для железа, SIM и трекеров (только цифры, латиница и разделители)
    private val hardwareOcr = Tesseract().apply {
        setDatapath("C:/tessdata")
        setLanguage("eng")
        setOcrEngineMode(1) // LSTM
        setPageSegMode(6)   // Единый блок текста
        setVariable("user_defined_dpi", "300")
        setVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-:/. ")
    }

    // 2. Профиль для табличек калибровки (полный русско-английский текст)
    private val fullTextOcr = Tesseract().apply {
        setDatapath("C:/tessdata")
        setLanguage("rus+eng")
        setOcrEngineMode(1)
        setPageSegMode(3)   // Автоматическая сегментация
        setVariable("user_defined_dpi", "300")
    }

    /**
     * Основной метод: делает быстрый прогон по железному профилю,
     * а если данных недостаточно — подключает полный языковой профиль.
     */
    fun extractTextDual(imageFile: File): Pair<String, String> {
        val original = ImageIO.read(imageFile) ?: return Pair("", "")
        val preprocessed = preprocess(original)

        // Читаем строгим железным профилем
        val hardwareText = try {
            hardwareOcr.doOCR(preprocessed).trim()
        } catch (e: Throwable) {
            ""
        }

        // Читаем полным профилем (для мастерских, тахографов и моделей)
        val fullText = try {
            fullTextOcr.doOCR(preprocessed).trim()
        } catch (e: Throwable) {
            ""
        }

        println(">>> [HARDWARE OCR RAW]:\n$hardwareText\n-------------------")
        println(">>> [FULL OCR RAW]:\n$fullText\n===================")

        return Pair(hardwareText, fullText)
    }

    fun preprocess(original: BufferedImage): BufferedImage {
        // 1. Увеличение разрешения в 2.5 раза с бикубической интерполяцией
        val targetW = (original.width * 2.5).toInt()
        val targetH = (original.height * 2.5).toInt()
        val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)

        val g2d = scaled.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.drawImage(original, 0, 0, targetW, targetH, null)
        g2d.dispose()

        // 2. Повышение резкости (Sharpen Matrix 3x3)
        // Усиливает границы мелких цифр и разделяющих двоеточий
        val sharpenKernel = floatArrayOf(
            0.0f, -1.0f,  0.0f,
            -1.0f,  5.0f, -1.0f,
            0.0f, -1.0f,  0.0f
        )
        val sharpenOp = ConvolveOp(Kernel(3, 3, sharpenKernel), ConvolveOp.EDGE_NO_OP, null)
        val sharpened = sharpenOp.filter(scaled, null)

        // 3. Подтягивание контраста (растягиваем динамический диапазон)
        // Умножаем яркость на 1.35 и сдвигаем уровень черного
        val rescaleOp = RescaleOp(1.35f, -20.0f, null)
        val contrasted = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
        rescaleOp.filter(sharpened, contrasted)

        return contrasted
    }
}