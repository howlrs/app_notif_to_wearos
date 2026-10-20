package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputRulesTest {
    @Test
    fun parsesValuesInRange() {
        assertEquals(101, InputRules.parseSiteId("101"))
        assertEquals(999_999, InputRules.parseSiteId("999999"))
        assertEquals(5, InputRules.parseDeviceId("5"))
        assertEquals(999, InputRules.parseDeviceId("999"))
    }

    @Test
    fun ignoresLeadingZeros() {
        assertEquals(5, InputRules.parseDeviceId("05"))
        assertEquals(101, InputRules.parseSiteId("000101"))
    }

    @Test
    fun rejectsEmptyNonDigitAndOutOfRange() {
        assertNull(InputRules.parseSiteId(""))
        assertNull(InputRules.parseSiteId("0"))
        assertNull(InputRules.parseSiteId("1000000"))
        assertNull(InputRules.parseSiteId("10a"))
        assertNull(InputRules.parseSiteId("-1"))
        assertNull(InputRules.parseDeviceId("0"))
        assertNull(InputRules.parseDeviceId("1000"))
        assertNull(InputRules.parseDeviceId(" 5"))
    }

    @Test
    fun sanitizeKeepsDigitsUpToMaxLength() {
        assertEquals("101", InputRules.sanitize("1a0-1", 6))
        assertEquals("123", InputRules.sanitize("12345", InputRules.DEVICE_ID_MAX_LENGTH))
    }

    @Test
    fun formatsDeviceIdWithTwoDigits() {
        assertEquals("05", InputRules.formatDeviceId(5))
        assertEquals("42", InputRules.formatDeviceId(42))
        assertEquals("100", InputRules.formatDeviceId(100))
    }
}
