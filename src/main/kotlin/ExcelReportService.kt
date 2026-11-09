package com.example

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter

object ExcelReportService {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun generateReport(records: List<DeviceRecord>): File {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Оборудование")

        // Стиль для заголовков
        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        // Заголовки без столбца "Сырые данные"
        val headers = listOf(
            "№",
            "Дата/Время",
            "Тип",
            "Производитель",
            "Модель",
            "IMEI",
            "Номер телефона",
            "ICCID"
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, title ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(title)
            cell.cellStyle = headerStyle
        }

        // Заполнение строк
        records.forEachIndexed { index, record ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(record.timestamp.format(dateFormatter))
            row.createCell(2).setCellValue(record.objectType.title)
            row.createCell(3).setCellValue(record.manufacturer)
            row.createCell(4).setCellValue(record.model)
            row.createCell(5).setCellValue(record.imei)
            row.createCell(6).setCellValue(record.phoneNumber)
            row.createCell(7).setCellValue(record.iccid)
        }

        // Автоширина для всех 8 столбцов
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
        }

        val reportsDir = File("reports")
        if (!reportsDir.exists()) reportsDir.mkdirs()

        val file = File(reportsDir, "report_${System.currentTimeMillis()}.xlsx")
        FileOutputStream(file).use { out ->
            workbook.write(out)
        }
        workbook.close()

        return file
    }
}