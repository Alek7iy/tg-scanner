package com.example

object TesseractAnalyzer {

    // -------------------------------------------------------------
    // Стандартные шаблоны идентификаторов (ISO / ITU / 3GPP)
    // -------------------------------------------------------------
    private val IMEI_REGEX = Regex("""\b([38]\d{14})\b""") // GSM TAC: 15 цифр, начинаются на 3 или 8
    private val ICCID_REGEX = Regex("""\b(89\d{16,18})\b""") // ITU-T E.118: 18-20 цифр, начинаются на 89
    private val MAC_COLON_REGEX = Regex("""\b([0-9A-F]{2}(?::[0-9A-F]{2}){5})\b""") // BLE MAC: строго с двоеточиями
    private val VIN_REGEX = Regex("""\b([A-HJ-NPR-Z0-9]{17})\b""") // ISO 3779: 17 символов без I, O, Q

    fun tryParseFromOcr(
        hardwareText: String,
        fullText: String,
        barcodeValue: String? = null
    ): ParsedRecognitionResult? {
        if (hardwareText.isBlank() && fullText.isBlank() && barcodeValue.isNullOrBlank()) return null

        val upperHardware = hardwareText.uppercase()
        val upperFull = fullText.uppercase()
        val combined = "$upperHardware\n$upperFull"

        // Очищенный сжатый текст для поиска слитных последовательностей
        val compactText = (upperHardware + " " + upperFull + " " + (barcodeValue ?: ""))
            .replace(Regex("""[\s\-_:/\\|]"""), "")

        // =========================================================
        // 1. ШАБЛОН: ТЕРМИНАЛ МОНИТОРИНГА (GPS / ГЛОНАСС)
        // =========================================================
        val validImei = (barcodeValue?.takeIf { it.length == 15 && it.all { c -> c.isDigit() } && (it.startsWith("8") || it.startsWith("3")) })
            ?: IMEI_REGEX.find(hardwareText)?.value
            ?: Regex("""(?:ID|IMEI)[:\s]*([38]\d{14})""").find(combined)?.groupValues?.get(1)
            ?: IMEI_REGEX.find(compactText)?.value

        // Исключаем ложное срабатывание на тахографы с надписью Drive Smart
        val isTachoMention = combined.contains("ТАХОГРАФ") || combined.contains("DRIVE SMART") || combined.contains("КАЛИБРОВ")

        val isNavtelecom = !isTachoMention && (
                combined.contains("НАВТЕЛЕКОМ") || combined.contains("NAVTELECOM") ||
                        combined.contains("SMART S") || combined.contains("СМАРТ S") ||
                        combined.contains("SIGNAL S") || combined.contains("СИГНАЛ S") ||
                        combined.contains("S-47") || combined.contains("S-26") || combined.contains("S-24")
                )

        val isAutograf = combined.contains("ТЕХНОКОМ") || combined.contains("TECHNOKOM") ||
                combined.contains("АВТОГРАФ") || combined.contains("ABTOIPA") || combined.contains("AUTOGRAF")

        val isUmka = combined.contains("UMKA") || combined.contains("УМКА") || combined.contains("ВЕГА")
        val isTerminalContext = isNavtelecom || isAutograf || isUmka ||
                combined.contains("TELTONIKA") || combined.contains("GALILEO")

        val termSn = Regex("""(?:S/N|SN|С/Н|№)[:\s]*([0-9]{6,12})""", RegexOption.IGNORE_CASE).find(combined)?.groupValues?.get(1)
            ?: (if (isAutograf) Regex("""\b(0\d{6})\b""").find(hardwareText)?.value else null)
            ?: Regex("""\b(4752\d{8}|2423\d{8}|242\d{9})\b""").find(compactText)?.value
            ?: ""

        // Терминал создается ТОЛЬКО если есть валидный IMEI ЛИБО подтвержденный бренд с серийником
        if (validImei != null || (isTerminalContext && termSn.isNotBlank())) {
            val maker = when {
                isAutograf -> "НПО ТехноКом"
                isNavtelecom -> "Навтелеком"
                isUmka -> "Вега-Абсолют"
                combined.contains("TELTONIKA") -> "Teltonika"
                else -> "Терминал"
            }

            val modelTerm = when {
                isAutograf -> if (combined.contains("GSM")) "АвтоГРАФ-GSM" else "АвтоГРАФ"
                combined.contains("4752") -> "СИГНАЛ S-4752"
                combined.contains("2651") -> "СИГНАЛ S-2651"
                combined.contains("2423") -> "СМАРТ S-2423"
                combined.contains("2425") -> "СМАРТ S-2425"
                isUmka -> "УМКа"
                else -> if (isNavtelecom) "Навтелеком" else "Терминал GPS"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.TERMINAL,
                manufacturer = maker,
                model = modelTerm,
                serialNumber = termSn,
                imei = validImei ?: "",
                rawText = "$hardwareText\n$fullText"
            )
        }

        // =========================================================
        // 2. ШАБЛОН: SIM-КАРТА (ITU-T E.118)
        // =========================================================
        val iccidFromBarcode = barcodeValue?.filter { it.isDigit() }?.takeIf { it.startsWith("89") && it.length in 18..20 }
        val iccidFromText = ICCID_REGEX.find(compactText)?.value
        val validIccid = iccidFromBarcode ?: iccidFromText

        val phoneMatch = Regex("""(?:\+7|8)[\s(-]*(\d{3})[\s)-]*(\d{3})[\s-]*(\d{2})[\s-]*(\d{2})""")
            .find(fullText.ifBlank { hardwareText })
            ?.let { "+7${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}${it.groupValues[4]}" }

        val isSimBrand = combined.contains("МЕГАФОН") || combined.contains("MEGAFON") ||
                combined.contains("МТС") || combined.contains("MTS") ||
                combined.contains("БИЛАЙН") || combined.contains("BEELINE") ||
                combined.contains("Т2") || combined.contains("TELE2") ||
                combined.contains("NEXT MOBILE")

        if (validIccid != null || (isSimBrand && phoneMatch != null)) {
            val operator = when {
                validIccid?.startsWith("8970102") == true || combined.contains("МЕГАФОН") -> "МегаФон"
                validIccid?.startsWith("8970120") == true || combined.contains("TELE2") || combined.contains("Т2") -> "Т2 (Tele2)"
                validIccid?.startsWith("8970101") == true || combined.contains("МТС") -> "МТС"
                validIccid?.startsWith("8970199") == true || combined.contains("NEXT") -> "Next Mobile"
                validIccid?.startsWith("8970199") == true || combined.contains("БИЛАЙН") -> "Билайн"
                else -> "SIM-карта"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.SIM_CARD,
                manufacturer = operator,
                model = "SIM-карта",
                iccid = validIccid ?: "",
                phoneNumber = phoneMatch ?: "",
                rawText = "$hardwareText\n$fullText"
            )
        }

        // =========================================================
        // 3. ШАБЛОН: ТАХОГРАФ / КАЛИБРОВОЧНАЯ ТАБЛИЧКА (ISO 3779)
        // =========================================================
        val isTachoContext = combined.contains("ТАХОГРАФ") || combined.contains("КАЛИБРОВ") ||
                combined.contains("МАСТЕРСК") || combined.contains("СКЗИ") || combined.contains("НКМ") ||
                combined.contains("ПАРАМЕТР") || combined.contains("W=") || combined.contains("DRIVE SMART")

        // 1. Извлекаем строку после маркера VIN и вычищаем пробелы, точки и тире
        val vinAfterMarker = Regex("""(?:VIN|ВИН|V1N)[:\s]*([0-9A-ZА-Я\s\-_]{15,25})""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)?.replace(Regex("""[\s\-_.:]"""), "")

        val standaloneVin = if (isTachoContext) {
            Regex("""\b([XZWL0-9][0-9A-ZА-Я]{16})\b""").find(compactText)?.value
        } else null

        val rawVin = vinAfterMarker ?: standaloneVin

        val validVin = rawVin?.map { char ->
            when (char) {
                'А' -> 'A'; 'В' -> 'B'; 'Е' -> 'E'; 'К' -> 'K'; 'М' -> 'M'
                'Н' -> 'H'; 'Р' -> 'P'; 'С' -> 'C'; 'Т' -> 'T'; 'Х' -> 'X'
                'О', 'O', 'Q' -> '0' // В VIN не бывает O/Q, это цифра 0
                'I' -> '1'           // В VIN не бывает I, это цифра 1
                else -> char
            }
        }?.joinToString("")?.take(17)?.takeIf { it.length >= 15 }

        if (isTachoContext || validVin != null) {
            // 2. Госномер (VRN / ГРЗ)
            val explicitVrn = Regex("""(?:VRN|ГРЗ|VRIN|VRM)[:\s]*([A-ZА-Я0-9]{6,10})""").find(combined)?.groupValues?.get(1)
            val patternVrn = Regex("""([А-ЯA-Z]\d{3}[А-ЯA-Z]{2}\d{2,3})""").find(compactText)?.groupValues?.get(1)
            val vrn = explicitVrn ?: patternVrn ?: ""

            // 3. Серийный номер тахографа (C/H, C/N, S/N)
            val serial = Regex("""(?:C/H|С/H|C/N|С/Н|S/N|SN|-/H|СИН)[:\s]*([0-9A-ZА-Я /]{4,25})""")
                .find(combined)?.groupValues?.get(1)
                ?.lines()?.firstOrNull()?.trim()
                ?: Regex("""\b(0[0-9A-F]{15})\b""").find(compactText)?.value
                ?: ""

            // 4. Номер блока СКЗИ / НКМ (строка с префиксом НКМ)
            val explicitNkm = Regex("""(?:НКМ|СКЗИ|HKM)[:\s]*([0-9\s]{14,22})""")
                .find(combined)?.groupValues?.get(1)?.filter { it.isDigit() }

            val standaloneNkm = Regex("""\b(2[56]\d{14})\b""").find(compactText)?.value

            val nkm = Regex("""(?:НКМ|СКЗИ|HKM)[:\s]*([0-9\s]{14,22})""")
                .find(combined)?.groupValues?.get(1)?.filter { it.isDigit() }
                ?: Regex("""\b(2[56]\d{14})\b""").find(compactText)?.value
                ?: ""

            // 1. Прямое считывание значения из строки "Тахограф: <модель>"
            val explicitTachoModel = Regex("""(?:Тахограф|Тахограф:)\s*([A-Za-zА-Яа-я0-9\-_ ]{4,30})""", RegexOption.IGNORE_CASE)
                .find(hardwareText + "\n" + fullText)?.groupValues?.get(1)?.trim()

            // 2. Нормализация по шаблонам реестра тахографов РФ (латиница + кириллица)
            val modelTacho = when {
                combined.contains("ATOL") || combined.contains("АТОЛ") ||
                        combined.contains("DRIVE") || combined.contains("BRIVE") -> {
                    if (combined.contains("SMART") || combined.contains("СМАРТ")) "Атол Drive Smart" else "Атол Drive 5"
                }
                combined.contains("ШТРИХ") || combined.contains("TAXORUS") || combined.contains("TAXO RUS") -> "Штрих-Тахо RUS"
                combined.contains("МЕРКУРИЙ") || combined.contains("ТА-001") -> "Меркурий ТА-001"
                combined.contains("VDO") || combined.contains("DTCO") -> "VDO DTCO 3283"
                combined.contains("МИКАС") -> "МИКАС"
                !explicitTachoModel.isNullOrBlank() -> explicitTachoModel
                else -> "Тахограф"
            }

            val workshop = if (combined.contains("НАВИТЕХ")) "ООО \"НАВИТЕХ\"" else "Мастерская РФ"

            return ParsedRecognitionResult(
                objectType = ObjectType.TACHOGRAPH_PLATE,
                manufacturer = workshop,
                model = modelTacho,
                serialNumber = serial,
                vin = validVin ?: "",
                vrn = vrn,
                nkm = nkm,
                rawText = "$hardwareText\n$fullText"
            )
        }

        // =========================================================
        // 4. ШАБЛОН: КАМЕРА ВИДЕОНАБЛЮДЕНИЯ (CARVIS и др.)
        // =========================================================
        val isCameraContext = combined.contains("CARVIS") || combined.contains("КАМЕРА") ||
                combined.contains("ВИДЕОНАБЛЮДЕНИ") || combined.contains("MC-404")

        if (isCameraContext) {
            val cameraSn = if (!barcodeValue.isNullOrBlank() && barcodeValue.all { it.isDigit() }) {
                barcodeValue
            } else {
                Regex("""(?:SN|S/N|С/Н)[:\s]*([0-9A-Z]{10,18})""").find(combined)?.groupValues?.get(1)
                    ?: Regex("""\b(04\d{12,14})\b""").find(compactText)?.value
                    ?: ""
            }

            val cameraModel = Regex("""(?:МОДЕЛЬ|MODEL)[:\s]*([A-Z0-9\-_]+(?:\s+[A-Z0-9\-_]+)?)""")
                .find(combined)?.groupValues?.get(1)?.trim()
                ?: if (combined.contains("MC-404IR") || combined.contains("404IR")) "CARVIS MC-404IR"
                else "Камера видеонаблюдения"

            return ParsedRecognitionResult(
                objectType = ObjectType.CAMERA,
                manufacturer = if (combined.contains("CARVIS")) "CARVIS" else "Видеонаблюдение",
                model = cameraModel,
                serialNumber = cameraSn,
                rawText = "$hardwareText\n$fullText"
            )
        }

        // =========================================================
        // 5. ШАБЛОН: ДАТЧИК УРОВНЯ ТОПЛИВА (ДУТ)
        // Строгое правило: MAC только с двоеточиями или явный маркер + S/N
        // =========================================================
        val isDutMark = combined.contains("ESCORT") || combined.contains("ЭСКОРТ") ||
                combined.contains("OMNICOMM") || combined.contains("ОМНИКОММ") ||
                combined.contains("LLS") || combined.contains("TD-BLE") || combined.contains("TD BLE")

        // 1. Поиск цельного MAC в одну строку (XX:XX:XX:XX:XX:XX)
        val inlineMac = MAC_COLON_REGEX.find(upperHardware)?.value
            ?: MAC_COLON_REGEX.find(upperFull)?.value

        // 2. Поиск двухстрочного MAC / MAC с шумом (DA:55:99: @ \n E3:DA:6D)
        val multilineMac = if (inlineMac == null) {
            // Ищем 6 hex-октетов, разделенных двоеточиями, допуская мусорные знаки между строками
            val robustMatch = Regex(
                """([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})[^0-9A-F\n\r]*[\r\n\s]+([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})"""
            ).find(combined)

            if (robustMatch != null) {
                "${robustMatch.groupValues[1]}:${robustMatch.groupValues[2]}"
            } else {
                // Если двоеточие между октетами потерялось/склеилось: собираем 6 байт через сжатие текста возле слова MAC
                val macContext = combined.lines()
                    .filter { it.contains(":") && it.any { c -> c.isDigit() } }
                    .joinToString(" ")
                val hexOctets = Regex("""\b([0-9A-F]{2})\b""").findAll(macContext.replace(":", " "))
                    .map { it.value }
                    .toList()
                if (hexOctets.size >= 6) {
                    hexOctets.take(6).joinToString(":")
                } else null
            }
        } else null

        // 3. MAC из штрихкода (если сканировался QR/Code128)
        val barcodeMac = barcodeValue?.takeIf { it.replace(":", "").length == 12 && !it.startsWith("89") }?.let {
            val clean = it.replace(":", "").uppercase()
            clean.chunked(2).joinToString(":")
        }

        val validMac = inlineMac ?: multilineMac ?: barcodeMac

        // 1. Поиск S/N с явным префиксом (S/N, SN, №, тире)
        val explicitSn = Regex("""(?:S/N|SN|С/Н|№|-)\s*([0-9]{6,12})""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)

        // 2. Поиск стандартного S/N Эскорт (начинается на 26, длина 10-12 цифр) в исходном тексте
        val escortSnText = Regex("""(?:\b|[^0-9])(26\d{8,10})(?:\b|[^0-9])""")
            .find(combined)?.groupValues?.get(1)

        // 3. Fallback в compactText (допускаем буквы перед 26, но цифры строго изолированы)
        val escortSnCompact = Regex("""(?:^|[^0-9])(26\d{8,10})(?:[^0-9]|$)""")
            .find(compactText)?.groupValues?.get(1)

        val dutSn = explicitSn ?: escortSnText ?: escortSnCompact ?: ""

        if (validMac != null || (isDutMark && dutSn.isNotBlank())) {
            val brand = when {
                combined.contains("ESCORT") || combined.contains("ЭСКОРТ") || combined.contains("TD") -> "Эскорт"
                combined.contains("OMNICOMM") || combined.contains("ОМНИКОММ") || combined.contains("LLS") -> "Omnicomm"
                else -> "ДУТ"
            }

            val modelDut = when {
                combined.contains("TD-BLE") || combined.contains("BLE") -> "Эскорт TD-BLE"
                combined.contains("TD-150") -> "Эскорт TD-150"
                combined.contains("LLS-5") -> "Omnicomm LLS 5"
                combined.contains("LLS-4") -> "Omnicomm LLS 4"
                else -> if (brand == "Эскорт") "Эскорт TD-BLE" else "ДУТ"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.DUT,
                manufacturer = brand,
                model = modelDut,
                serialNumber = dutSn,
                macAddress = validMac ?: "",
                rawText = "$hardwareText\n$fullText"
            )
        }

        return null
    }
}