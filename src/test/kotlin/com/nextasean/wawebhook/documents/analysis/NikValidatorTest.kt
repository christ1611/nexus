package com.nextasean.wawebhook.documents.analysis

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NikValidatorTest {

    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun `well-formed male NIK matching the card passes with region and DOB decoded`() {
        val v = NikValidator.validate("3171051705900001", "1990-05-17", "LAKI-LAKI", today)
        assertTrue(v.valid, v.issues.toString())
        assertEquals("DKI Jakarta", v.provinceName)
        assertEquals("71", v.regencyCode)
        assertEquals("05", v.districtCode)
        assertEquals(LocalDate.of(1990, 5, 17), v.dobFromNik)
        assertEquals(Gender.MALE, v.genderFromNik)
        assertEquals(true, v.dobMatches)
        assertEquals(true, v.genderMatches)
    }

    @Test
    fun `female NIK adds 40 to the day and must agree with the stated gender`() {
        val ok = NikValidator.validate("3201014808950002", "08-08-1995", "PEREMPUAN", today)
        assertTrue(ok.valid, ok.issues.toString())
        assertEquals(LocalDate.of(1995, 8, 8), ok.dobFromNik)
        assertEquals(Gender.FEMALE, ok.genderFromNik)

        val mismatch = NikValidator.validate("3201014808950002", "1995-08-08", "LAKI-LAKI", today)
        assertFalse(mismatch.valid)
        assertEquals(false, mismatch.genderMatches)
        assertTrue(mismatch.issues.single().contains("+40 rule"), mismatch.issues.toString())
    }

    @Test
    fun `date of birth in the NIK must match the stated date, century ignored`() {
        val v = NikValidator.validate("3171051705900001", "1990-05-18", "LAKI-LAKI", today)
        assertFalse(v.valid)
        assertEquals(false, v.dobMatches)
        assertTrue(v.issues.single().startsWith("Date of birth encoded in NIK (17-05-1990) does not match"), v.issues.toString())

        // Two-digit year 01 could be 1901 or 2001; the stated year decides.
        val young = NikValidator.validate("3171050101010003", "2001-01-01", "LAKI-LAKI", today)
        assertEquals(LocalDate.of(2001, 1, 1), young.dobFromNik)
        assertEquals(true, young.dobMatches)
    }

    @Test
    fun `length, digits, province, zero codes and serial are checked`() {
        assertEquals(listOf("NIK must be 16 digits (got 15)"), NikValidator.validate("317105170590000", today = today).issues)
        assertEquals(listOf("NIK must contain digits only"), NikValidator.validate("31710517059000O1", today = today).issues)

        val bad = NikValidator.validate("9900001705900000", today = today)
        assertContains(bad.issues, "Unknown province code 99")
        assertContains(bad.issues, "Regency/city code 00 is not valid")
        assertContains(bad.issues, "District code 00 is not valid")
        assertContains(bad.issues, "Serial number 0000 is not valid")
        assertFalse(bad.regionValid)

        val sameDigits = NikValidator.validate("1111111111111111", today = today)
        assertContains(sameDigits.issues, "NIK digits are all identical")
    }

    @Test
    fun `impossible dates inside the NIK are reported`() {
        assertTrue(NikValidator.validate("3171051713900001", today = today).issues.any { it.contains("Month of birth") })
        assertTrue(NikValidator.validate("3171057201900001", today = today).issues.any { it.contains("Day of birth") })
        assertTrue(NikValidator.validate("3171053002900001", today = today).issues.any { it.contains("not a real date") })
    }

    @Test
    fun `stated date and gender accept the forms printed on a KTP`() {
        assertEquals(LocalDate.of(1990, 5, 17), NikValidator.parseDate("JAKARTA, 17-05-1990"))
        assertEquals(LocalDate.of(1990, 5, 17), NikValidator.parseDate("17/05/1990"))
        assertEquals(LocalDate.of(1990, 5, 17), NikValidator.parseDate("1990-05-17"))
        assertNull(NikValidator.parseDate("unknown"))
        assertEquals(Gender.FEMALE, NikValidator.parseGender("Perempuan"))
        assertEquals(Gender.MALE, NikValidator.parseGender("LAKI - LAKI"))
        assertNull(NikValidator.parseGender(""))

        val unparsable = NikValidator.validate("3171051705900001", "seventeenth of May", "LAKI-LAKI", today)
        assertNull(unparsable.dobMatches)
        assertTrue(unparsable.issues.single().contains("could not be parsed"))
    }
}
