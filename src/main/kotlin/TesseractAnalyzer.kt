package com.example

object TesseractAnalyzer {

    fun tryParseFromOcr(
        hardwareText: String,
        fullText: String,
        barcodeValue: String? = null
    ): ParsedRecognitionResult? {
        if (hardwareText.isBlank() && fullText.isBlank() && barcodeValue.isNullOrBlank()) return null

        val upperHardware = hardwareText.uppercase()
        val upperFull = fullText.uppercase()
        val combined = "$upperHardware\n$upperFull"

        // Сжатая строка без пробелов
        val cleanCompact = (upperHardware + " " + (barcodeValue ?: "")).replace(Regex("""[\s\-_:/\\|]"""), "")

        // ==========================================
        // 1. ТЕРМИНАЛ МОНИТОРИНГА (GPS / ГЛОНАСС)
        // ==========================================
        val isNavtelecom = combined.contains("НАВТЕЛЕКОМ") || combined.contains("NABTENEKO") ||
                combined.contains("HAPTEЛEKOM") || combined.contains("NAVTELECOM")

        val isTerminalMark = isNavtelecom ||
                combined.contains("GALILEOSKY") || combined.contains("ГАЛИЛЕО") ||
                combined.contains("TELTONIKA") || combined.contains("ARNAVI") ||
                combined.contains("SMART") || combined.contains("CMAP") ||
                combined.contains("S-2") || combined.contains("S.2") || combined.contains("8-2") ||
                combined.contains("IMEI")

        val rawImei = Regex("""\b([893]\d{14})\b""").find(hardwareText)?.groupValues?.get(1)
            ?: Regex("""IMEI[:\s]*(\d{15})""").find(combined)?.groupValues?.get(1)
            ?: Regex("""\b(\d{15})\b""").find(cleanCompact)?.groupValues?.get(1)

        val imei = rawImei?.let {
            if (it.startsWith("96")) "86" + it.substring(2) else it
        } ?: ""

        if (imei.isNotBlank() || isTerminalMark) {
            val explicitSn = Regex("""(?:S/N|SN|С/Н|№)[:\s]*([0-9]{4,14})""").find(combined)?.groupValues?.get(1)
            val navtelecomSn = Regex("""\b(2[34]\d{6,10})\b""").find(hardwareText)?.groupValues?.get(1)
            val standaloneSn = Regex("""(?m)^\s*([0-9]{8,12})\s*$""").find(hardwareText)?.groupValues?.get(1)
                ?: Regex("""\b([0-9]{8,12})\b""").find(hardwareText)?.groupValues?.get(1)

            val sn = explicitSn ?: navtelecomSn ?: standaloneSn ?: ""

            val maker = when {
                isNavtelecom || combined.contains("SMART") || combined.contains("CMAP") -> "Навтелеком"
                combined.contains("GALILEOSKY") || combined.contains("ГАЛИЛЕО") -> "НПО ГалилеоСкай"
                combined.contains("TELTONIKA") -> "Teltonika"
                combined.contains("ARNAVI") -> "Аруснави"
                else -> "Терминал"
            }

            val modelTerm = when {
                combined.contains("2423") -> "СМАРТ S-2423"
                combined.contains("2425") -> "СМАРТ S-2425"
                combined.contains("2435") -> "СМАРТ S-2435"
                combined.contains("2420") -> "СМАРТ S-2420"
                combined.contains("2421") -> "СМАРТ S-2421"
                combined.contains("2433") -> "СМАРТ S-2433"
                combined.contains("2333") || combined.contains("2334") -> "СМАРТ S-2333"
                combined.contains("FMB920") -> "Teltonika FMB920"
                combined.contains("7X") -> "Galileosky 7x"
                combined.contains("SMART") || combined.contains("CMAP") -> "СМАРТ S-серия"
                else -> "Терминал GPS"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.TERMINAL,
                manufacturer = maker,
                model = modelTerm,
                serialNumber = sn,
                imei = imei,
                rawText = "$hardwareText\n$fullText"
            )
        }

        // ==========================================
        // 2. КАЛИБРОВОЧНАЯ ТАБЛИЧКА ТАХОГРАФА / ТС
        // ==========================================
        if (combined.contains("МАСТЕРСК") || combined.contains("ТАХОГРАФ") || combined.contains("ТРАНСПОРТ") ||
            combined.contains("КАЛИБРОВ") || combined.contains("VRN") || combined.contains("НКМ") ||
            combined.contains("СКЗИ") || combined.contains("ПАРАМЕТР")) {

            // 1. Поиск VIN (17 знаков)
            // Ищем строку, начинающуюся с типичных символов российского VIN: X89, X, Z, W и т.д.
            val vinCandidate = Regex("""(?:V[AI]N|BA[HX]|B[AI]N)?([XХZЖW][0-9A-ZА-Я]{16})""").find(cleanCompact)?.groupValues?.get(1)
                ?: Regex("""\b([A-ZА-Я0-9]{17})\b""").find(cleanCompact)?.value ?: ""

            // Нормализуем русские буквы-двойники в латиницу
            val vin = vinCandidate.map { char ->
                when (char) {
                    'А' -> 'A'; 'В' -> 'B'; 'Е' -> 'E'; 'К' -> 'K'; 'М' -> 'M'
                    'Н' -> 'H'; 'О' -> 'O'; 'Р' -> 'P'; 'С' -> 'C'; 'Т' -> 'T'; 'Х' -> 'X'
                    else -> char
                }
            }.joinToString("")

            // 2. Поиск госномера (VRN)
            val explicitVrn = Regex("""VRN[:\s]*([A-ZА-Я0-9]{6,10})""").find(combined)?.groupValues?.get(1)
            val patternVrn = Regex("""([А-ЯA-Z]\d{3}[А-ЯA-Z]{2}\d{2,3})""").find(cleanCompact)?.groupValues?.get(1)
            val vrn = explicitVrn ?: patternVrn ?: ""

            // 3. Серийный номер тахографа (C/N, C/H)
            // Ищем после маркеров CH / CN / CE / СH в сжатой строке
            val explicitSerial = Regex("""(?:C[HNE]|С[HNE]|SN|CH)[:\s]*([0-9A-ZА-Я]{14,18})""").find(cleanCompact)?.groupValues?.get(1)
            // Фоллбэк: 16 hex-символов (характерно для серийников Атол Drive)
            val hex16Serial = Regex("""\b(0[0-9A-F]{15})\b""").find(cleanCompact)?.groupValues?.get(1)
            val serial = explicitSerial ?: hex16Serial ?: ""

            // 4. Номер блока СКЗИ / НКМ
            // Номера НКМ начинаются с кода года/партии (обычно "26" или "25") и имеют длину 14-16 символов
            val explicitNkm = Regex("""(?:НКМ|СКЗИ|HKM|SKI|HKIM)[:\s]*([0-9A-Z]{12,20})""").find(cleanCompact)?.groupValues?.get(1)
            val standaloneNkm = Regex("""(2[56][0-9A-Z]{12,16})""").find(cleanCompact)?.groupValues?.get(1)
            val nkm = explicitNkm ?: standaloneNkm ?: ""

            val workshop = if (combined.contains("НАВИТЕХ")) "ООО НАВИТЕХ" else "Мастерская РФ"
            val modelTacho = when {
                combined.contains("АТОЛ") || combined.contains("DRIVE") -> "Атол Drive"
                combined.contains("ШТРИХ") -> "Штрих-Тахо RUS"
                combined.contains("МЕРКУРИЙ") -> "Меркурий ТА-001"
                combined.contains("VDO") || combined.contains("DTCO") -> "VDO DTCO"
                else -> "Тахограф"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.TACHOGRAPH_PLATE,
                manufacturer = workshop,
                model = modelTacho,
                serialNumber = serial,
                vin = vin,
                vrn = vrn,
                nkm = nkm,
                rawText = "$hardwareText\n$fullText"
            )
        }

        // ==========================================
        // 3. ДАТЧИК УРОВНЯ ТОПЛИВА (ДУТ)
        // ==========================================
        val isEscort = combined.contains("ESCORT") || combined.contains("ЭСКОРТ") ||
                combined.contains("SEORT") || combined.contains("SCORT") || combined.contains("ESORT")

        val isOmnicomm = combined.contains("OMNICOMM") || combined.contains("ОМНИКОММ") || combined.contains("LLS")

        val isDutMark = isEscort || isOmnicomm ||
                combined.contains("TD BLE") || combined.contains("TD-BLE") ||
                combined.contains("TD-") || combined.contains("ДУТ") || combined.contains("DUT")

        // 1. Серийный номер ДУТ
        val barcodeSn = if (!barcodeValue.isNullOrBlank()) {
            Regex("""\b(26\d{8,10})\b""").find(barcodeValue)?.groupValues?.get(1)
        } else null

        val explicitSn = Regex("""(?:S/N|SN|С/Н|№)[:\s]*([0-9]{5,14})""").find(upperHardware)?.groupValues?.get(1)
        val escortSn = Regex("""\b(26\d{8,10})\b""").find(hardwareText)?.groupValues?.get(1)
        val standaloneSn = Regex("""\b(\d{8,12})\b""").find(hardwareText)?.groupValues?.get(1)

        val sn = barcodeSn ?: explicitSn ?: escortSn ?: standaloneSn ?: ""

        // 2. MAC-адрес из штрихкода/QR (если распознан)
        val barcodeMac = if (!barcodeValue.isNullOrBlank()) {
            val cleanBarcode = barcodeValue.uppercase().replace(Regex("""[^0-9A-F]"""), "")
            Regex("""([0-9A-F]{12})""").find(cleanBarcode)?.groupValues?.get(1)?.chunked(2)?.joinToString(":")
        } else null

        // 3. MAC-адрес из OCR текста:
        // Сначала ищем полный вариант (с допуском мусорного символа спереди, например 1D9:B5...)
        val full6Match = Regex("""(?:^|[^0-9A-F])([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})""")
            .find(upperHardware)?.groupValues?.get(1)

        // Вариант, когда потеряно последнее двоеточие (например, 69:B5:68:89:0A9C)
        val missingColonMatch = if (full6Match == null) {
            Regex("""(?:^|[^0-9A-F])([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{4})""")
                .find(upperHardware)?.groupValues?.get(1)?.let {
                    it.substring(0, 11) + ":" + it.substring(11, 13) + ":" + it.substring(13, 15)
                }
        } else null

        // Вариант с отсечением единичного мусорного символа в начале (1D9:B5:68:89:...)
        val gluedPrefixMatch = if (full6Match == null && missingColonMatch == null) {
            Regex("""[0-9A-Z]([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{1,2}(?::[0-9A-F]{1,2})*)""")
                .find(upperHardware)?.groupValues?.get(1)
        } else null

        val rawMac = full6Match ?: missingColonMatch ?: gluedPrefixMatch ?: ""

        val normalizedMac = rawMac.split(":")
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString(":")

        val textMac = if (normalizedMac.replace(":", "") == sn) "" else normalizedMac

        val mac = barcodeMac ?: textMac

        if (isDutMark || mac.isNotBlank() || sn.isNotBlank()) {
            val brand = when {
                isEscort -> "Эскорт"
                isOmnicomm -> "Omnicomm"
                combined.contains("ТЕХНОТОН") -> "Технотон"
                else -> "ДУТ"
            }

            val modelDut = when {
                combined.contains("BLE") || combined.contains("TD BLE") || combined.contains("TD-BLE") -> "Эскорт TD-BLE"
                combined.contains("TD-150") || combined.contains("TD 150") -> "Эскорт TD-150"
                combined.contains("TD-600") || combined.contains("TD 600") -> "Эскорт TD-600"
                combined.contains("LLS-5") || combined.contains("LLS 5") -> "Omnicomm LLS 5"
                combined.contains("LLS-4") || combined.contains("LLS 4") -> "Omnicomm LLS 4"
                else -> if (isEscort) "Эскорт TD-BLE" else "ДУТ"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.DUT,
                manufacturer = brand,
                model = modelDut,
                serialNumber = sn,
                macAddress = mac,
                rawText = "$hardwareText\n$fullText"
            )
        }

        // ==========================================
        // 4. SIM-КАРТА (Проверяется только если это НЕ терминал)
        // ==========================================
        val isSimContext = combined.contains("ICC") || combined.contains("SIM") ||
                combined.contains("ТЕЛЕ2") || combined.contains("T2") ||
                combined.contains("МЕГАФОН") || combined.contains("МТС") || combined.contains("БИЛАЙН")

        val barcodeIccid = if (barcodeValue != null && (barcodeValue.startsWith("89") || barcodeValue.length >= 18)) {
            barcodeValue.filter { it.isDigit() }
        } else null

        val textIccid = Regex("""89\d{16,18}""").find(cleanCompact)?.value
        val fallbackIccid = if (combined.contains("ICC")) {
            val digitsAfterIcc = combined.substringAfter("ICC").take(30).filter { it.isDigit() }
            if (digitsAfterIcc.length >= 6) {
                if (digitsAfterIcc.startsWith("89")) digitsAfterIcc else "89$digitsAfterIcc"
            } else null
        } else null

        val finalIccid = barcodeIccid ?: textIccid ?: fallbackIccid ?: ""

        // Ищем телефон: только 10-11 цифр и ТОЛЬКО если есть контекст SIM
        val phoneMatch = if (isSimContext) {
            Regex("""(?:\+7|8)[\s(-]*(\d{3})[\s)-]*(\d{3})[\s-]*(\d{2})[\s-]*(\d{2})""").find(fullText.ifBlank { hardwareText })
        } else null

        val phoneNumber = phoneMatch?.let {
            "+7${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}${it.groupValues[4]}"
        } ?: ""

        if (finalIccid.isNotBlank() || (isSimContext && phoneNumber.isNotBlank())) {
            val operator = when {
                finalIccid.startsWith("8970120") || combined.contains("ТЕЛЕ2") || combined.contains("T2") -> "Т2 (Tele2)"
                finalIccid.startsWith("8970102") || combined.contains("МЕГАФОН") -> "МегаФон"
                finalIccid.startsWith("8970199") || combined.contains("NEXT") -> "Next Mobile"
                finalIccid.startsWith("8970101") || combined.contains("МТС") -> "МТС"
                finalIccid.startsWith("8970199") || combined.contains("БИЛАЙН") -> "Билайн"
                else -> "SIM-карта"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.SIM_CARD,
                manufacturer = operator,
                model = "SIM-карта",
                iccid = finalIccid,
                phoneNumber = phoneNumber,
                rawText = "$hardwareText\n$fullText"
            )
        }

        return null
    }
}