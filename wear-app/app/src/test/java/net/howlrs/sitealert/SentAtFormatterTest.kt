package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SentAtFormatterTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun showsSendTimeInLocalZone() {
        val now = Instant.parse("2026-10-19T01:15:30Z")
        assertEquals("10:15 送信", SentAtFormatter.describe("2026-10-19T10:15:00+09:00", now, tokyo))
    }

    @Test
    fun addsDelayWhenReceivedLate() {
        val now = Instant.parse("2026-10-19T01:27:00Z")
        assertEquals("10:15 送信（12分前）", SentAtFormatter.describe("2026-10-19T10:15:00+09:00", now, tokyo))
    }

    @Test
    fun ignoresMissingOrInvalidValues() {
        val now = Instant.now()
        assertNull(SentAtFormatter.describe(null, now, tokyo))
        assertNull(SentAtFormatter.describe("yesterday", now, tokyo))
    }
}
