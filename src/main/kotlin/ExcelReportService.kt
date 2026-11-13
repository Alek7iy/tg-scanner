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

        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val headers = listOf(
            "№",
            "Дата/Время",
            "Тип",
            "Мастерская/Производитель",
            "Модель / Тахограф",
            "VIN",
            "Госномер (VRN)",
            "C/N / S/N",
            "НКМ (СКЗИ)",
            "Срок действия СКЗИ",
            "Пробег (км)",
            "Калибровка (w / шины)",
            "CAN1",
            "MAC (ДУТ)",
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

        records.forEachIndexed { index, record ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(record.timestamp.format(dateFormatter))
            row.createCell(2).setCellValue(record.objectType.title)
            row.createCell(3).setCellValue(record.manufacturer)
            row.createCell(4).setCellValue(record.model)
            row.createCell(5).setCellValue(record.vin)
            row.createCell(6).setCellValue(record.vrn)
            row.createCell(7).setCellValue(record.serialNumber)
            row.createCell(8).setCellValue(record.nkm)
            row.createCell(9).setCellValue(record.nkmExpiry)
            row.createCell(10).setCellValue(record.mileage)
            row.createCell(11).setCellValue(record.calibrationInfo)
            row.createCell(12).setCellValue(record.canInfo)
            row.createCell(13).setCellValue(record.macAddress)
            row.createCell(14).setCellValue(record.imei)
            row.createCell(15).setCellValue(record.phoneNumber ?: "")
            row.createCell(16).setCellValue(record.iccid ?: "")
        }

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