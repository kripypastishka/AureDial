package dev.auredial.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialSearchTest {
    @Test
    fun matchesCyrillicLatinAndAccents() {
        assertTrue(t9Matches("24352", "Алёна", "+12025550123"))
        assertTrue(t9Matches("5673", "José", "+12025550123"))
        assertTrue(t9Matches("4225", "Ли-Иван", "+12025550123"))
        assertTrue(t9Matches("4", "Йонас", "+12025550123"))
        assertFalse(t9Matches("5555", "Алёна", "+12025550123"))
    }

    @Test
    fun matchesFormattedPhoneNumbersAndRejectsServiceCodesAsSearch() {
        assertTrue(t9Matches("2555", null, "+1 (202) 555-0123"))
        assertFalse(t9Matches("*123#", null, "+1 (202) 555-0123"))
        assertFalse(t9Matches("", null, "+1 (202) 555-0123"))
    }
}
