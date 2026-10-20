package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSessionTest {
    private fun alert(id: String) = AlertMessage(
        AlertMessage.Type.ALERT, id, "警告", "内容 $id", 101, 5, null,
        if (id == "A") 1 else 2,
    )

    @Test
    fun oldScreenCannotAcknowledgeNewAlert() {
        val session = AlertSession()
        session.start(alert("A"))
        val displayedId = session.currentMessage!!.alertId
        session.start(alert("B"))

        assertFalse(session.acknowledge(displayedId, 1))
        assertEquals(alert("B"), session.currentMessage)
        assertTrue(session.acknowledge("B", 2))
        assertNull(session.currentMessage)
    }

    @Test
    fun newAlertAfterAcknowledgementRemainsActive() {
        val session = AlertSession()
        session.start(alert("A"))
        assertTrue(session.acknowledge("A", 1))
        session.start(alert("B"))

        assertEquals(alert("B"), session.currentMessage)
        assertFalse(session.acknowledge("A", 1))
    }

    @Test
    fun delayedReplacementCannotDismissLaterAlert() {
        val session = AlertSession()
        session.start(alert("A"))
        session.start(alert("B"))

        assertNull(session.dismiss("A", 1))
        assertEquals(alert("B"), session.currentMessage)
    }

    @Test
    fun hashCollisionDoesNotAcknowledgeAnotherAlert() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val session = AlertSession()
        session.start(alert("BB"))

        assertFalse(session.acknowledge("Aa", 2))
        assertEquals("BB", session.currentMessage?.alertId)
    }

    @Test
    fun missingIdAndRepeatedAcknowledgementsHaveNoEffect() {
        val session = AlertSession()
        assertFalse(session.acknowledge("A", 1))
        session.start(alert("A"))

        assertFalse(session.acknowledge(null, 1))
        assertFalse(session.acknowledge("", 1))
        assertFalse(session.acknowledge(" ", 1))
        assertEquals("A", session.currentMessage?.alertId)
        assertTrue(session.acknowledge("A", 1))
        assertFalse(session.acknowledge("A", 1))
        assertNull(session.currentMessage)
    }
}
