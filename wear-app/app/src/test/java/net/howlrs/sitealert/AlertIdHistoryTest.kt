package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertIdHistoryTest {
    @Test
    fun addsNewIdsAndRejectsDuplicates() {
        val first = AlertIdHistory.add(emptyList(), "a")!!
        assertEquals(listOf("a"), first)
        assertNull(AlertIdHistory.add(first, "a"))
    }

    @Test
    fun keepsOnlyRecentIds() {
        var history = emptyList<String>()
        repeat(AlertIdHistory.CAPACITY + 5) { history = AlertIdHistory.add(history, "id$it")!! }
        assertEquals(AlertIdHistory.CAPACITY, history.size)
        assertEquals("id5", history.first())
        // 押し出された古い ID は再び受け付ける
        assertEquals("id0", AlertIdHistory.add(history, "id0")!!.last())
    }

    @Test
    fun roundTripsThroughStorage() {
        val history = listOf("0:1700000000%abc", "3f6c2a1e-7d4b")
        assertEquals(history, AlertIdHistory.decode(AlertIdHistory.encode(history)))
        assertEquals(emptyList<String>(), AlertIdHistory.decode(null))
        assertEquals(emptyList<String>(), AlertIdHistory.decode(""))
    }
}
