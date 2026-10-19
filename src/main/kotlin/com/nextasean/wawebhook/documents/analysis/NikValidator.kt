package com.nextasean.wawebhook.documents.analysis

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class Gender { MALE, FEMALE }

/**
 * Checks of a NIK (Nomor Induk Kependudukan) against its documented structure:
 * `PP KK CC DDMMYY SSSS` = province, regency/city, district, date of birth (day +40 for women), serial.
 */
data class NikValidation(
    val nik: String,
    val formatValid: Boolean,
    val provinceCode: String?,
    val provinceName: String?,
    val regencyCode: String?,
    val districtCode: String?,
    val regionValid: Boolean,
    /** Date of birth encoded in the NIK; the century is resolved against the stated date when there is one. */
    val dobFromNik: LocalDate?,
    val genderFromNik: Gender?,
    val statedDob: LocalDate?,
    /** Null when either side is missing. */
    val dobMatches: Boolean?,
    val statedGender: Gender?,
    /** Null when either side is missing. */
    val genderMatches: Boolean?,
    val issues: List<String>,
) {
    val valid: Boolean get() = issues.isEmpty()
}

object NikValidator {

    /** Province codes per Kemendagri, including the provinces created in 2022. */
    val PROVINCES: Map<String, String> = mapOf(
        "11" to "Aceh", "12" to "Sumatera Utara", "13" to "Sumatera Barat", "14" to "Riau", "15" to "Jambi",
        "16" to "Sumatera Selatan", "17" to "Bengkulu", "18" to "Lampung", "19" to "Kepulauan Bangka Belitung",
        "21" to "Kepulauan Riau", "31" to "DKI Jakarta", "32" to "Jawa Barat", "33" to "Jawa Tengah",
        "34" to "DI Yogyakarta", "35" to "Jawa Timur", "36" to "Banten", "51" to "Bali", "52" to "Nusa Tenggara Barat",
        "53" to "Nusa Tenggara Timur", "61" to "Kalimantan Barat", "62" to "Kalimantan Tengah", "63" to "Kalimantan Selatan",
        "64" to "Kalimantan Timur", "65" to "Kalimantan Utara", "71" to "Sulawesi Utara", "72" to "Sulawesi Tengah",
        "73" to "Sulawesi Selatan", "74" to "Sulawesi Tenggara", "75" to "Gorontalo", "76" to "Sulawesi Barat",
        "81" to "Maluku", "82" to "Maluku Utara", "91" to "Papua", "92" to "Papua Barat", "93" to "Papua Selatan",
        "94" to "Papua Tengah", "95" to "Papua Pegunungan", "96" to "Papua Barat Daya",
    )

    private const val FEMALE_OFFSET = 40

    fun validate(
        rawNik: String,
        statedDob: String? = null,
        statedGender: String? = null,
        today: LocalDate = LocalDate.now(),
    ): NikValidation {
        val nik = rawNik.filter { !it.isWhitespace() }
        val issues = mutableListOf<String>()
        val stated = parseDate(statedDob)
        if (statedDob != null && statedDob.isNotBlank() && stated == null) issues += "Stated date of birth \"$statedDob\" could not be parsed"
        val gender = parseGender(statedGender)

        val formatValid = nik.length == 16 && nik.all { it.isDigit() }
        if (!formatValid) {
            issues += when {
                nik.any { !it.isDigit() } -> "NIK must contain digits only"
                else -> "NIK must be 16 digits (got ${nik.length})"
            }
            return NikValidation(nik, false, null, null, null, null, false, null, null, stated, null, gender, null, issues)
        }
        if (nik.all { it == nik[0] }) issues += "NIK digits are all identical"

        val province = nik.substring(0, 2)
        val regency = nik.substring(2, 4)
        val district = nik.substring(4, 6)
        val provinceName = PROVINCES[province]
        if (provinceName == null) issues += "Unknown province code $province"
        if (regency == "00") issues += "Regency/city code 00 is not valid"
        if (district == "00") issues += "District code 00 is not valid"
        val regionValid = provinceName != null && regency != "00" && district != "00"

        val rawDay = nik.substring(6, 8).toInt()
        val month = nik.substring(8, 10).toInt()
        val yy = nik.substring(10, 12).toInt()
        val genderFromNik = if (rawDay > FEMALE_OFFSET) Gender.FEMALE else Gender.MALE
        val day = if (rawDay > FEMALE_OFFSET) rawDay - FEMALE_OFFSET else rawDay
        var dobFromNik: LocalDate? = null
        when {
            day !in 1..31 -> issues += "Day of birth in NIK (${nik.substring(6, 8)}) is out of range"
            month !in 1..12 -> issues += "Month of birth in NIK (${nik.substring(8, 10)}) is out of range"
            else -> {
                val year = resolveYear(yy, stated, today)
                dobFromNik = try {
                    LocalDate.of(year, month, day)
                } catch (e: DateTimeException) {
                    issues += "Date of birth encoded in NIK ($day/$month/$yy) is not a real date"
                    null
                }
            }
        }
        if (nik.substring(12) == "0000") issues += "Serial number 0000 is not valid"

        val dobMatches = if (dobFromNik != null && stated != null) {
            // Compare day, month and the two-digit year: the NIK cannot tell 1926 from 2026.
            dobFromNik.dayOfMonth == stated.dayOfMonth && dobFromNik.monthValue == stated.monthValue && dobFromNik.year % 100 == stated.year % 100
        } else {
            null
        }
        if (dobMatches == false) {
            issues += "Date of birth encoded in NIK (${dobFromNik!!.format(DMY)}) does not match the stated date of birth (${stated!!.format(DMY)})"
        }
        val genderMatches = if (gender != null && dobFromNik != null) gender == genderFromNik else null
        if (genderMatches == false) {
            issues += if (genderFromNik == Gender.FEMALE) {
                "NIK day digits ${nik.substring(6, 8)} encode a woman (+40 rule) but the card states ${statedGender?.trim()}"
            } else {
                "NIK day digits ${nik.substring(6, 8)} encode a man but the card states ${statedGender?.trim()}"
            }
        }

        return NikValidation(
            nik = nik,
            formatValid = true,
            provinceCode = province,
            provinceName = provinceName,
            regencyCode = regency,
            districtCode = district,
            regionValid = regionValid,
            dobFromNik = dobFromNik,
            genderFromNik = genderFromNik,
            statedDob = stated,
            dobMatches = dobMatches,
            statedGender = gender,
            genderMatches = genderMatches,
            issues = issues,
        )
    }

    /** Picks the century: the stated year wins when it ends in the same two digits, otherwise the latest year not in the future. */
    private fun resolveYear(yy: Int, stated: LocalDate?, today: LocalDate): Int {
        if (stated != null && stated.year % 100 == yy) return stated.year
        val thisCentury = (today.year / 100) * 100 + yy
        return if (thisCentury <= today.year) thisCentury else thisCentury - 100
    }

    private val DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("dd MM yyyy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.forLanguageTag("id-ID")),
        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
    )

    /** Accepts ISO and the day-first forms printed on Indonesian documents; the part after a comma ("JAKARTA, 17-05-1990") is used. */
    fun parseDate(raw: String?): LocalDate? {
        val text = raw?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in DATE_FORMATS) {
            try {
                return LocalDate.parse(text, f)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    fun parseGender(raw: String?): Gender? = when (raw?.trim()?.uppercase()?.replace('-', ' ')?.replace(Regex("\\s+"), " ")) {
        null, "" -> null
        "LAKI LAKI", "LAKI", "PRIA", "MALE", "L", "M" -> Gender.MALE
        "PEREMPUAN", "WANITA", "FEMALE", "P", "F" -> Gender.FEMALE
        else -> null
    }
}
