package com.example

enum class EditField {
    MODEL,
    IDENTIFIER, // IMEI или телефон/ICCID
    MANUFACTURER,
    DUT_MODEL,   // ESCORT TD BLE
    DUT_SERIAL,  // серийный номер ДУТ
    DUT_MAC      // MAC-адрес
}


data class UserInputState(
    val recordId: String,
    val field: EditField
)

