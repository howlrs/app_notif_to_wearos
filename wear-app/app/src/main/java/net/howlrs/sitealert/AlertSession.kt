package net.howlrs.sitealert

/** 最新の警報だけを鳴らす。「確認」は、その画面・通知が示した警報にだけ適用する。 */
class AlertSession {
    var currentMessage: AlertMessage? = null
        private set

    fun start(message: AlertMessage) {
        currentMessage = message
    }

    fun acknowledge(alertId: String?, notificationId: Int): Boolean {
        return dismiss(alertId, notificationId) != null
    }

    /** 確認・新着置換のどちらでも、表示中の同一通知だけを終了対象にする。 */
    fun dismiss(alertId: String?, notificationId: Int): AlertMessage? {
        if (
            alertId.isNullOrBlank() || notificationId <= 0 ||
            currentMessage?.alertId != alertId || currentMessage?.notificationId != notificationId
        ) return null
        return currentMessage.also { currentMessage = null }
    }
}
