package com.example

import java.time.LocalDateTime
import java.util.UUID

enum class ObjectType(val title: String) {
    TERMINAL("Терминал"),
    SIM_CARD("SIM-карта"),
    DUT("ДУТ"),
    TACHOGRAPH_PLATE("Калибровка тахографа / ТС"),
    UNKNOWN("Не определено")
}

data class DeviceRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: LocalDateTime = LocalDateTime.now(),
    var objectType: ObjectType,
    var manufacturer: String,
    var model: String,
    var serialNumber: String,
    var macAddress: String,
    var imei: String,
    var phoneNumber: String? = null,
    var iccid: String? = null,
    var vin: String,
    var vrn: String,
    var nkm: String,
    var rawText: String,
    var messageId: Int? = null
)

data class ParsedRecognitionResult(
    val objectType: ObjectType,
    val manufacturer: String,
    val model: String,
    val serialNumber: String = "",
    val macAddress: String = "",
    val imei: String = "",
    val phoneNumber: String = "",
    val iccid: String = "",
    val vin: String = "",
    val vrn: String = "",
    val nkm: String = "",
    val rawText: String = ""
)