package com.example

import java.io.File
import java.time.LocalDateTime

enum class ObjectType(val title: String) {
    TERMINAL("Терминал"),
    SIM_CARD("SIM-карта"),
    DUT("ДУТ"),
    UNKNOWN("Не определено")
}

data class DeviceRecord(
    val id: String,
    val timestamp: LocalDateTime,
    val objectType: ObjectType,
    var manufacturer: String = "-", // Производитель (Navtelecom и т.д.)
    var model: String = "-",        // SMART S-2423 и т.д.
    var imei: String = "-",         // IMEI терминала
    var phoneNumber: String = "-",  // Номер телефона SIM
    var iccid: String = "-",        // ICCID SIM-карты (18-20 цифр)
    var dutSerial: String = "-",    // Серийный номер ДУТ
    var dutMac: String = "-",       // MAC-адрес ДУТ
    val rawText: String             // Весь распознанный текст для страховки
)

interface ImageAnalyzer {
    fun analyze(imageFile: File): DeviceRecord
}