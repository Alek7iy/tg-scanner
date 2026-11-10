package com.example

enum class EditField {
    MODEL,
    IDENTIFIER,
    MANUFACTURER,
    DUT_SERIAL,
    DUT_MAC,
    VIN,
    VRN,
    NKM
}

data class UserInputState(
    val recordId: String,
    val field: EditField,
    val cardMessageId: Int? = null
)

