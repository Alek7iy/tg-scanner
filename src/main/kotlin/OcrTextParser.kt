package com.example

object OcrTextParser {

    // -------------------------------------------------------------
    // Стандартные шаблоны идентификаторов (ISO / ITU / 3GPP)
    // -------------------------------------------------------------
    private val IMEI_REGEX = Regex("""\b([38]\d{14})\b""") // GSM TAC: 15 цифр, начинаются на 3 или 8
    private val ICCID_REGEX = Regex("""\b(89\d{16,18})\b""") // ITU-T E.118: 18–20 цифр, начинаются на 89
    private val MAC_COLON_REGEX = Regex("""\b([0-9A-F]{2}(?::[0-9A-F]{2}){5})\b""") // BLE MAC: строго 6 октетов с двоеточиями
    private val VIN_REGEX = Regex("""\b([A-HJ-NPR-Z0-9]{17})\b""") // ISO 3779: 17 символов (без I, O, Q)
    // ГОСТ Р 50577: только 12 разрешенных букв (кириллица + латиница)
    private val VRN_AUTO_REGEX = Regex("""\b([АВЕКМНОРСТУХABEKMHOPCTYX]\d{3}[АВЕКМНОРСТУХABEKMHOPCTYX]{2})\s*(\d{2,3})\b""")
    private val VRN_TRAILER_REGEX = Regex("""\b([АВЕКМНОРСТУХABEKMHOPCTYX]{2}\d{4})\s*(\d{2,3})\b""")

    fun tryParseFromOcr(
        hardwareText: String,
        fullText: String,
        barcodeValue: String? = null
    ): ParsedRecognitionResult? {
        if (hardwareText.isBlank() && fullText.isBlank() && barcodeValue.isNullOrBlank()) return null

        val originalText = "$hardwareText\n$fullText".trim()
        val upperHardware = hardwareText.uppercase()
        val upperFull = fullText.uppercase()
        val combined = "$upperHardware\n$upperFull"

        // Очищенный сжатый текст для поиска слитных последовательностей
        val compactText = (upperHardware + " " + upperFull + " " + (barcodeValue ?: ""))
            .replace(Regex("""[\s\-_:/\\|]"""), "")

        // =========================================================
        // 1. ШАБЛОН: ТЕРМИНАЛ МОНИТОРИНГА (GPS / ГЛОНАСС)
        // =========================================================
        val validImei = barcodeValue?.takeIf { it.length == 15 && it.all(Char::isDigit) && (it.startsWith("8") || it.startsWith("3")) }
            ?: IMEI_REGEX.find(hardwareText)?.value
            ?: Regex("""(?:ID|IMEI)[:\s]*([38]\d{14})""").find(combined)?.groupValues?.get(1)
            ?: IMEI_REGEX.find(compactText)?.value

        val isTachoMention = combined.contains("ТАХОГРАФ") || combined.contains("КАЛИБРОВ")

        // Динамический поиск серии/модели Навтелекома (СМАРТ / СИГНАЛ / SMART / SIGNAL + S-XXXX)
        val navtelecomModelMatch = Regex("""((?:СМАРТ|SMART|СИГНАЛ|SIGNAL)[\s-]*S[\s-]*\d{4})""")
            .find(combined)
            ?: Regex("""\b(S-\d{4})\b""").find(combined)

        val navtelecomIndex = navtelecomModelMatch?.groupValues?.get(1)?.takeLast(4)

        val isNavtelecom = !isTachoMention && (
                navtelecomModelMatch != null ||
                        combined.contains("НАВТЕЛЕКОМ") || combined.contains("NAVTELECOM")
                )
        val isAutograf = combined.contains("ТЕХНОКОМ") || combined.contains("TECHNOKOM") ||
                combined.contains("АВТОГРАФ") || combined.contains("AUTOGRAF")
        val isUmka = combined.contains("УМКА") || combined.contains("UMKA") || combined.contains("ВЕГА")
        val isTeltonika = combined.contains("TELTONIKA")
        val isGalileo = combined.contains("GALILEO")

        val isTerminalContext = isNavtelecom || isAutograf || isUmka || isTeltonika || isGalileo

        // Серийный номер: по явной метке (S/N, SN, С/Н, №) или по 4-значному префиксу модели
        val termSn = Regex("""(?:S/N|SN|С/Н|№)[:\s]*([0-9A-Z]{6,15})""").find(combined)?.groupValues?.get(1)
            ?: (if (navtelecomIndex != null) Regex("""\b(${navtelecomIndex}\d{5,8})\b""").find(combined)?.groupValues?.get(1) else null)
            ?: (if (isAutograf) Regex("""\b(0\d{6})\b""").find(hardwareText)?.value else null)
            ?: ""

        if (validImei != null || (isTerminalContext && termSn.isNotBlank())) {
            val maker = when {
                isNavtelecom -> "Навтелеком"
                isAutograf -> "НПО ТехноКом"
                isUmka -> "Вега-Абсолют"
                isTeltonika -> "Teltonika"
                isGalileo -> "GalileoSky"
                else -> "Терминал"
            }

            val modelTerm = when {
                navtelecomModelMatch != null -> navtelecomModelMatch.groupValues[1].replace(Regex("""\s+"""), " ")
                isAutograf -> Regex("""(АВТОГРАФ(?:-[A-Z0-9]+)?)""").find(combined)?.groupValues?.get(1) ?: "АвтоГРАФ"
                isUmka -> Regex("""(УМКА[\s-]*\d+|UMKA[\s-]*\d+)""").find(combined)?.groupValues?.get(1) ?: "УМКа"
                isTeltonika -> Regex("""(FM[A-Z0-9]{3,6})""").find(combined)?.groupValues?.get(1) ?: "Teltonika"
                else -> maker
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.TERMINAL,
                manufacturer = maker,
                model = modelTerm,
                serialNumber = termSn,
                imei = validImei ?: "",
                rawText = originalText
            )
        }

        // =========================================================
        // 2. ШАБЛОН: SIM-КАРТА (ITU-T E.118)
        // =========================================================
        val iccidFromBarcode = barcodeValue?.filter(Char::isDigit)?.takeIf { it.startsWith("89") && it.length in 18..20 }
        val validIccid = iccidFromBarcode ?: ICCID_REGEX.find(compactText)?.value

        val phoneMatch = Regex("""(?:\+7|8)[\s(-]*(\d{3})[\s)-]*(\d{3})[\s-]*(\d{2})[\s-]*(\d{2})""")
            .find(fullText.ifBlank { hardwareText })
            ?.let { "+7${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}${it.groupValues[4]}" }

        val isSimBrand = combined.contains("МЕГАФОН") || combined.contains("MEGAFON") ||
                combined.contains("МТС") || combined.contains("MTS") ||
                combined.contains("БИЛАЙН") || combined.contains("BEELINE") ||
                combined.contains("TELE2") || combined.contains("Т2") ||
                combined.contains("NEXT MOBILE")

        if (validIccid != null || (isSimBrand && phoneMatch != null)) {
            val operator = when {
                validIccid?.startsWith("8970102") == true || combined.contains("МЕГАФОН") || combined.contains("MEGAFON") -> "МегаФон"
                validIccid?.startsWith("8970120") == true || combined.contains("TELE2") || combined.contains("Т2") -> "Т2 (Tele2)"
                validIccid?.startsWith("8970101") == true || combined.contains("МТС") || combined.contains("MTS") -> "МТС"
                combined.contains("NEXT MOBILE") -> "Next Mobile"
                validIccid?.startsWith("8970199") == true || combined.contains("БИЛАЙН") || combined.contains("BEELINE") -> "Билайн"
                else -> "SIM-карта"
            }

            return ParsedRecognitionResult(
                objectType = ObjectType.SIM_CARD,
                manufacturer = operator,
                model = "SIM-карта",
                iccid = validIccid ?: "",
                phoneNumber = phoneMatch ?: "",
                rawText = originalText
            )
        }
        // =========================================================
        // 3.1. ШАБЛОН: РАСПЕЧАТКА ТЕХДАННЫХ ТАХОГРАФА (ЧЕК)
        // =========================================================
        val isTachoReceipt = combined.contains("UTC_SU") ||
                combined.contains("В ПЕРЕЧНЕ") ||
                combined.contains("ДОП.ПАРАМЕТРЫ") ||
                (combined.contains("СРОК ДЕЙСТВИЯ") && combined.contains("ЗАВ.N"))

        if (isTachoReceipt) {
            val vin = VIN_REGEX.find(compactText)?.value ?: ""
            val vrn = Regex("""(?:RUS/)?([А-ЯA-Z]\d{3}[А-ЯA-Z]{2}\d{2,3})""").find(compactText)?.groupValues?.get(1) ?: ""
            val serial = Regex("""\b([0-9A-F]{16})\b""").find(combined)?.groupValues?.get(1) ?: ""
            val nkm = Regex("""ЗАВ\.?\s*N[:\s]*([0-9A-Z]{12,20})""").find(combined)?.groupValues?.get(1) ?: ""
            val nkmExpiry = Regex("""СРОК ДЕЙСТВИЯ[:\s]*(\d{2}\.\d{2}\.\d{4})""").find(combined)?.groupValues?.get(1) ?: ""
            val mileage = Regex("""\b(\d{1,3}(?:\s?\d{3})+)\s*КМ\b""").find(combined)?.groupValues?.get(1)?.replace(" ", "") ?: ""

            val modelTacho = Regex("""\b(Drive\s+(?:Base|Smart|5)|Штрих-Тахо\s*RUS|Меркурий\s*ТА-001|DTCO\s*3283)\b""", RegexOption.IGNORE_CASE)
                .find(originalText)?.groupValues?.get(1) ?: "Тахограф"

            val workshop = Regex("""((?:ООО|ИП|АО)\s*["«][^"»\r\n]+["»])""")
                .find(originalText)?.groupValues?.get(1)?.trim() ?: "Мастерская РФ"

            // Калибровка и CAN (опционально в дополнительные поля или примечание)
            val wCoef = Regex("""\bw\s*(\d[\d\s]{2,6})\s*имп/км""", RegexOption.IGNORE_CASE).find(originalText)?.groupValues?.get(1)?.replace(" ", "") ?: ""
            val tires = Regex("""\b(\d{3}/\d{2}R\d{2}(?:\.\d)?)\b""").find(originalText)?.groupValues?.get(1) ?: ""
            val canSpeed = Regex("""CAN1\s*Speed[:\s]*([^\r\n]+)""", RegexOption.IGNORE_CASE).find(originalText)?.groupValues?.get(1)?.trim() ?: ""

            return ParsedRecognitionResult(
                objectType = ObjectType.TACHOGRAPH_RECEIPT,
                manufacturer = workshop,
                model = modelTacho,
                serialNumber = serial,
                vin = vin,
                vrn = vrn,
                nkm = nkm,
                nkmExpiry = nkmExpiry,
                mileage = mileage,
                calibrationInfo = if (wCoef.isNotEmpty() || tires.isNotEmpty()) "w=$wCoef, $tires".trim(',', ' ') else "",
                canInfo = canSpeed,
                rawText = originalText
            )
        }

        // =========================================================
        // 3.2. ШАБЛОН: ТАХОГРАФ / КАЛИБРОВОЧНАЯ ТАБЛИЧКА (ISO 3779)
        // =========================================================
        val isTachoContext = combined.contains("ТАХОГРАФ") || combined.contains("КАЛИБРОВ") ||
                combined.contains("МАСТЕРСК") || combined.contains("СКЗИ") || combined.contains("НКМ") ||
                combined.contains("ПАРАМЕТР") || combined.contains("W=")

        val rawVin = Regex("""(?:VIN|ВИН)[:\s]*([0-9A-Z\s\-_]{15,25})""")
            .find(combined)?.groupValues?.get(1)?.replace(Regex("""[\s\-_.:]"""), "")
            ?: if (isTachoContext) VIN_REGEX.find(compactText)?.value else null

        val validVin = rawVin?.take(17)?.takeIf { it.length == 17 } ?: ""

        if (isTachoContext || validVin.isNotEmpty()) {
            // Госномер (VRN / ГРЗ)
            val vrn = Regex("""(?:VRN|ГРЗ)[:\s]*([A-ZА-Я0-9]{6,10})""").find(combined)?.groupValues?.get(1)
                ?: Regex("""([А-ЯA-Z]\d{3}[А-ЯA-Z]{2}\d{2,3})""").find(compactText)?.groupValues?.get(1)
                ?: ""

            // Серийный номер тахографа: по метке (C/H, C/N, S/N) ИЛИ стандартный 16-значный HEX-номер (Атол Drive)
            val serial = Regex("""(?:C/H|С/H|C/N|С/Н|S/N|SN)[:\s]*([0-9A-Z\-/]{4,25})""")
                .find(combined)?.groupValues?.get(1)
                ?.lines()?.firstOrNull()?.trim()
                ?: Regex("""\b([0-9A-F]{16})\b""").find(combined)?.groupValues?.get(1)
                ?: ""

            // Номер блока СКЗИ / НКМ — строго по метке НКМ/СКЗИ
            val nkm = Regex("""(?:НКМ|НKМ|HKM|СКЗИ)[:\s]*([0-9A-Z]{12,20})""")
                .find(combined)?.groupValues?.get(1)
                ?: ""

            // Модель тахографа: из "Тахограф: <модель>" или "Тахограф <модель>." (на коробке)
            val modelTacho = Regex("""Тахограф[:\s]+([^.\r\n]+)""", RegexOption.IGNORE_CASE)
                .find(originalText)?.groupValues?.get(1)?.trim()
                ?: "Тахограф"

            // Наименование мастерской — напрямую из юридического названия (ООО/ИП/АО "...")
            val workshop = Regex("""((?:ООО|ИП|АО)\s*["«][^"»\r\n]+["»])""")
                .find(originalText)?.groupValues?.get(1)?.trim()
                ?: if (combined.contains("МАСТЕРСК") || validVin.isNotEmpty()) "Мастерская РФ" else "Новый (коробка)"

            return ParsedRecognitionResult(
                objectType = ObjectType.TACHOGRAPH_PLATE,
                manufacturer = workshop,
                model = modelTacho,
                serialNumber = serial,
                vin = validVin,
                vrn = vrn,
                nkm = nkm,
                rawText = originalText
            )
        }

        // =========================================================
        // 4. ШАБЛОН: КАМЕРА ВИДЕОНАБЛЮДЕНИЯ (CARVIS и др.)
        // =========================================================
        val isCameraContext = combined.contains("CARVIS") || combined.contains("КАМЕРА") ||
                combined.contains("ВИДЕОНАБЛЮДЕНИ")

        if (isCameraContext) {
            val cameraSn = barcodeValue?.takeIf { it.all(Char::isDigit) }
                ?: Regex("""(?:SN|S/N|С/Н)[:\s]*([0-9A-Z]{8,18})""").find(combined)?.groupValues?.get(1)
                ?: ""

            val cameraModel = Regex("""(?:МОДЕЛЬ|MODEL)[:\s]*([A-Z0-9\-_]+(?:\s+[A-Z0-9\-_]+)?)""")
                .find(combined)?.groupValues?.get(1)?.trim()
                ?: Regex("""(MC-\d{3}[A-Z0-9-]*)""").find(combined)?.groupValues?.get(1)
                ?: "Камера видеонаблюдения"

            return ParsedRecognitionResult(
                objectType = ObjectType.CAMERA,
                manufacturer = if (combined.contains("CARVIS")) "CARVIS" else "Видеонаблюдение",
                model = cameraModel,
                serialNumber = cameraSn,
                rawText = originalText
            )
        }

        // =========================================================
        // 5. ШАБЛОН: ДАТЧИК УРОВНЯ ТОПЛИВА (ДУТ)
        // =========================================================
        val isDutMark = combined.contains("ESCORT") || combined.contains("ЭСКОРТ") ||
                combined.contains("OMNICOMM") || combined.contains("ОМНИКОММ") ||
                combined.contains("LLS") || combined.contains("TD-BLE") || combined.contains("TD BLE")

        // 1. Цельный MAC (XX:XX:XX:XX:XX:XX)
        val inlineMac = MAC_COLON_REGEX.find(upperHardware)?.value
            ?: MAC_COLON_REGEX.find(upperFull)?.value

        // 2. Двухстрочный MAC (XX:XX:XX \n XX:XX:XX)
        val multilineMac = if (inlineMac == null) {
            Regex("""([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})\s+([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})""")
                .find(combined)
                ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
        } else null

        // 3. MAC из штрихкода
        val barcodeMac = barcodeValue?.takeIf { it.replace(":", "").length == 12 && !it.startsWith("89") }?.let {
            it.replace(":", "").uppercase().chunked(2).joinToString(":")
        }

        val validMac = inlineMac ?: multilineMac ?: barcodeMac

        val dutSn = Regex("""(?:S/N|SN|С/Н|№)[:\s]*([0-9]{6,12})""").find(combined)?.groupValues?.get(1)
            ?: (if (isDutMark) Regex("""\b(\d{10,12})\b""").find(combined)?.groupValues?.get(1) else null)
            ?: ""

        if (validMac != null || (isDutMark && dutSn.isNotBlank())) {
            val brand = when {
                combined.contains("ESCORT") || combined.contains("ЭСКОРТ") || combined.contains("TD-") -> "Эскорт"
                combined.contains("OMNICOMM") || combined.contains("ОМНИКОММ") || combined.contains("LLS") -> "Omnicomm"
                else -> "ДУТ"
            }

            val modelDut = Regex("""((?:TD|LLS)[\s-]*[A-Z0-9]+)""").find(combined)?.groupValues?.get(1)
                ?: brand

            return ParsedRecognitionResult(
                objectType = ObjectType.DUT,
                manufacturer = brand,
                model = modelDut,
                serialNumber = dutSn,
                macAddress = validMac ?: "",
                rawText = originalText
            )
        }
        // =========================================================
        // 6. ШАБЛОН: ГОСНОМЕР ТС / ПРИЦЕПА (ГОСТ Р 50577)
        // Идет строго после тахографа, чтобы не перехватывать его табличку
        // =========================================================
        val autoPlateMatch = VRN_AUTO_REGEX.find(combined)
        val trailerPlateMatch = if (autoPlateMatch == null) VRN_TRAILER_REGEX.find(combined) else null
        val plateMatch = autoPlateMatch ?: trailerPlateMatch

        if (plateMatch != null) {
            // Приводим буквы к единому стандарту (кириллица) для аккуратного отображения в карточке
            val seriesAndDigits = plateMatch.groupValues[1].map { ch ->
                when (ch) {
                    'A' -> 'А'; 'B' -> 'В'; 'E' -> 'Е'; 'K' -> 'К'; 'M' -> 'М'; 'H' -> 'Н'
                    'O' -> 'О'; 'P' -> 'Р'; 'C' -> 'С'; 'T' -> 'Т'; 'Y' -> 'У'; 'X' -> 'Х'
                    else -> ch
                }
            }.joinToString("")

            val region = plateMatch.groupValues[2]
            val formattedPlate = "$seriesAndDigits $region" // Например: "Р912НН 193"

            return ParsedRecognitionResult(
                objectType = ObjectType.VEHICLE_PLATE,
                manufacturer = "РФ",
                model = if (trailerPlateMatch != null) "Прицеп" else "Транспорт",
                vrn = formattedPlate,
                rawText = originalText
            )
        }


        return null
    }
}