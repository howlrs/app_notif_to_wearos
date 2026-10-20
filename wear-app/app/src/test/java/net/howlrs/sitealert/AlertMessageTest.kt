package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertMessageTest {
    private val payload = mapOf(
        "type" to "alert",
        "alert_id" to "a-1",
        "site_id" to "101",
        "device_id" to "5",
        "title" to "警告",
        "body" to "測定値が閾値を超えました",
        "sent_at" to "2026-10-19T10:15:00+09:00",
    )

    @Test
    fun parsesAlertPayload() {
        val message = AlertMessage.fromData(payload, "fcm-id")!!
        assertEquals(AlertMessage.Type.ALERT, message.type)
        assertEquals("a-1", message.alertId)
        assertEquals(101, message.siteId)
        assertEquals(5, message.deviceId)
        assertEquals("警告", message.displayTitle)
        assertEquals("2026-10-19T10:15:00+09:00", message.sentAt)
    }

    @Test
    fun testMessagesArePrefixed() {
        val message = AlertMessage.fromData(payload + ("type" to "test"), null)!!
        assertEquals("【テスト】警告", message.displayTitle)
    }

    @Test
    fun ignoresUnknownType() {
        assertNull(AlertMessage.fromData(payload + ("type" to "clear"), "fcm-id"))
        assertNull(AlertMessage.fromData(payload - "type", "fcm-id"))
    }

    @Test
    fun fallsBackToMessageIdAndDefaults() {
        val message = AlertMessage.fromData(mapOf("type" to "alert"), "fcm-id")!!
        assertEquals("fcm-id", message.alertId)
        assertEquals(AlertMessage.DEFAULT_TITLE, message.title)
        assertEquals("", message.body)
        assertNull(message.siteId)
    }

    @Test
    fun requiresSomeIdentifier() {
        assertNull(AlertMessage.fromData(mapOf("type" to "alert"), null))
    }
}
