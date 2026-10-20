package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertNotificationStateTest {
    @Test
    fun oldAcknowledgementCannotCancelPendingNewNotification() {
        val storage = FakeStorage()
        val cancellations = mutableListOf<Int>()
        val pendingPosts = mutableListOf<Int>()
        val displayed = mutableSetOf<Int>()
        val coordinator = AlertNotificationCoordinator(AlertNotificationState(storage)) { cancellations += it }
        val first = AlertNotificationState(storage).assign(alert("A"))
        coordinator.start(first)
        pendingPosts += first.notificationId
        displayed += pendingPosts.removeAt(0)

        val second = AlertNotificationState(storage).assign(alert("B"))
        val replaced = coordinator.start(second)
        pendingPosts += second.notificationId // Binder 側ではまだ A が見えている想定
        coordinator.acknowledge(first.alertId, first.notificationId)
        displayed.removeAll(cancellations.toSet())
        displayed += pendingPosts.removeAt(0)

        assertEquals(listOf(first.notificationId, first.notificationId), cancellations)
        assertEquals(AlertNotificationRef(first.alertId, first.notificationId), replaced)
        assertFalse(cancellations.contains(second.notificationId))
        assertTrue(displayed.contains(second.notificationId))
    }

    @Test
    fun sequenceSurvivesStateRecreationAndDoesNotUseAlertIdHash() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val storage = FakeStorage()
        val first = AlertNotificationState(storage).assign(alert("Aa"))
        val second = AlertNotificationState(storage).assign(alert("BB"))

        assertEquals(1, first.notificationId)
        assertEquals(2, second.notificationId)
    }

    private fun alert(id: String) = AlertMessage(
        AlertMessage.Type.ALERT, id, "警告", "内容", 101, 5, null,
    )

    private class FakeStorage : AlertNotificationStateStorage {
        private var next = 0
        private var current: AlertNotificationRef? = null

        override fun nextNotificationId(): Int = ++next
        override fun current(): AlertNotificationRef? = current
        override fun saveCurrent(value: AlertNotificationRef?) { current = value }
    }
}
