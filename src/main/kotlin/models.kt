package com.example

import java.time.LocalDateTime
import java.util.UUID

enum class ObjectType(val title: String) {
    TERMINAL("Терминал"),
    SIM_CARD("SIM-карта"),
    DUT("ДУТ"),
    TACHOGRAPH_PLATE("Калибровка тахографа / ТС"),
    CAMERA("Камера видеонаблюдения"),
    VEHICLE_PLATE("Номер машины"),
    TACHOGRAPH_RECEIPT("Тех.данные тахографа"),
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
    var messageId: Int? = null,
    var nkmExpiry: String = "",
    var mileage: String = "",
    var calibrationInfo: String = "",
    var canInfo: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Дополняет пустые или нераспознанные поля текущего чека данными из следующей части распечатки */
    fun mergeReceipt(other: ParsedRecognitionResult): DeviceRecord {
        fun pick(current: String, incoming: String): String =
            if (current.isBlank() || current == "НЕ_РАСПОЗНАН") incoming.ifBlank { current } else current

        return copy(
            manufacturer = if (manufacturer == "Мастерская РФ" && other.manufacturer != "Мастерская РФ") other.manufacturer else manufacturer,
            model = if (model == "Тахограф" && other.model != "Тахограф") other.model else model,
            serialNumber = pick(serialNumber, other.serialNumber),
            vin = pick(vin, other.vin),
            vrn = pick(vrn, other.vrn),
            nkm = pick(nkm, other.nkm),
            nkmExpiry = pick(nkmExpiry, other.nkmExpiry),
            mileage = pick(mileage, other.mileage),
            calibrationInfo = pick(calibrationInfo, other.calibrationInfo),
            canInfo = pick(canInfo, other.canInfo),
            rawText = "$rawText\n---\n${other.rawText}"
        )
    }
}

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
    val rawText: String = "",
    val nkmExpiry: String = "",
    val mileage: String = "",
    val calibrationInfo: String = "",
    val canInfo: String = ""
)