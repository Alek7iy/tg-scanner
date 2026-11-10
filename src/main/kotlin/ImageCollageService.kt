package com.example

import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min

object ImageCollageService {

    /**
     * Склеивает от 1 до 6 фото в аккуратную сетку (коллаж).
     */
    fun createCollage(files: List<File>, targetDimension: Int = 2048): File {
        if (files.size == 1) return files.first()

        val count = min(files.size, 6)
        val cols = if (count <= 4) 2 else 3
        val rows = ceil(count.toDouble() / cols).toInt()

        val cellWidth = targetDimension / cols
        val cellHeight = targetDimension / rows

        val collage = BufferedImage(targetDimension, cellHeight * rows, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = collage.createGraphics()
        g.color = Color.DARK_GRAY
        g.fillRect(0, 0, collage.width, collage.height)

        for (i in 0 until count) {
            val file = files[i]
            val img = ImageIO.read(file) ?: continue

            val col = i % cols
            val row = i / cols
            val x = col * cellWidth
            val y = row * cellHeight

            val scale = min(cellWidth.toDouble() / img.width, cellHeight.toDouble() / img.height)
            val scaledW = (img.width * scale).toInt()
            val scaledH = (img.height * scale).toInt()
            val offsetX = x + (cellWidth - scaledW) / 2
            val offsetY = y + (cellHeight - scaledH) / 2

            g.drawImage(img.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH), offsetX, offsetY, null)
        }
        g.dispose()

        val output = File.createTempFile("collage_", ".jpg")
        ImageIO.write(collage, "jpg", output)
        return output
    }
}