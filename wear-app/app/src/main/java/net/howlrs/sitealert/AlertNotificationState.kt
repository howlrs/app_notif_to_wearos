package net.howlrs.sitealert

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** 通知枠と、現在利用者に提示すべき警報をアプリ側で対応付ける。 */
data class AlertNotificationRef(val alertId: String, val notificationId: Int)

internal interface AlertNotificationStateStorage {
    fun nextNotificationId(): Int
    fun current(): AlertNotificationRef?
    fun saveCurrent(value: AlertNotificationRef?)
}

/**
 * OS の activeNotifications は Binder 越しの過去スナップショットになり得るため、表示対象の判定には使わない。
 * 採番値と現在値を永続化し、プロセス再起動後も直前の通知 ID を再利用しない。
 */
class AlertNotificationState internal constructor(
    private val storage: AlertNotificationStateStorage,
) {
    constructor(context: Context) : this(SharedPreferencesAlertNotificationStorage(context.applicationContext))

    fun assign(message: AlertMessage): AlertMessage = synchronized(LOCK) {
        message.copy(notificationId = storage.nextNotificationId())
    }

    /** 最新の表示対象を差し替え、取り消すべき前の通知を返す。 */
    fun replace(message: AlertMessage): AlertNotificationRef? = synchronized(LOCK) {
        val current = ref(message)
        val previous = storage.current()
        storage.saveCurrent(current)
        previous?.takeIf { it != current }
    }

    fun isCurrent(message: AlertMessage): Boolean = synchronized(LOCK) {
        storage.current() == ref(message)
    }

    fun clearIfCurrent(alertId: String, notificationId: Int): Boolean = synchronized(LOCK) {
        if (storage.current() != AlertNotificationRef(alertId, notificationId)) return false
        storage.saveCurrent(null)
        true
    }

    private fun ref(message: AlertMessage): AlertNotificationRef {
        require(message.notificationId > 0) { "notificationId must be assigned before display" }
        return AlertNotificationRef(message.alertId, message.notificationId)
    }

    private companion object {
        val LOCK = Any()
    }
}

/** ID ごとの取消を一箇所に集め、古い確認操作が新着通知を消さないようにする。 */
class AlertNotificationCoordinator(
    private val state: AlertNotificationState,
    private val cancel: (Int) -> Unit,
) {
    /** 新着に置換し、旧通知の取消後にその警報を返す。 */
    fun start(message: AlertMessage): AlertNotificationRef? = state.replace(message)?.also {
        cancel(it.notificationId)
    }

    fun acknowledge(alertId: String, notificationId: Int) {
        state.clearIfCurrent(alertId, notificationId)
        // 表示対象が既に次の警報でも、この確認が指す古い通知だけは取り消せる。
        cancel(notificationId)
    }

}

private class SharedPreferencesAlertNotificationStorage(context: Context) : AlertNotificationStateStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun nextNotificationId(): Int {
        val previous = prefs.getInt(KEY_LAST_NOTIFICATION_ID, 0)
        check(previous < Int.MAX_VALUE) { "notification ID space exhausted" }
        val next = previous + 1
        prefs.edit(commit = true) { putInt(KEY_LAST_NOTIFICATION_ID, next) }
        return next
    }

    override fun current(): AlertNotificationRef? {
        val alertId = prefs.getString(KEY_CURRENT_ALERT_ID, null) ?: return null
        val notificationId = prefs.getInt(KEY_CURRENT_NOTIFICATION_ID, 0)
        return notificationId.takeIf { it > 0 }?.let { AlertNotificationRef(alertId, it) }
    }

    override fun saveCurrent(value: AlertNotificationRef?) {
        prefs.edit(commit = true) {
            if (value == null) {
                remove(KEY_CURRENT_ALERT_ID)
                remove(KEY_CURRENT_NOTIFICATION_ID)
            } else {
                putString(KEY_CURRENT_ALERT_ID, value.alertId)
                putInt(KEY_CURRENT_NOTIFICATION_ID, value.notificationId)
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "alert_notification_state"
        const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
        const val KEY_CURRENT_ALERT_ID = "current_alert_id"
        const val KEY_CURRENT_NOTIFICATION_ID = "current_notification_id"
    }
}
