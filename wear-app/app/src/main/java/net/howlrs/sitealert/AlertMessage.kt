package net.howlrs.sitealert

import android.content.Intent

/** FCM の data ペイロード（docs/03 §4.3）。値はすべて文字列で届く。 */
data class AlertMessage(
    val type: Type,
    val alertId: String,
    val title: String,
    val body: String,
    val siteId: Int?,
    val deviceId: Int?,
    val sentAt: String?,
) {
    enum class Type(val wire: String) { ALERT("alert"), TEST("test") }

    /** テスト通知はタイトルの先頭に「テスト」を付ける（docs/02 §5.8）。 */
    val displayTitle: String
        get() = if (type == Type.TEST) "【テスト】$title" else title

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_TYPE, type.wire)
        .putExtra(EXTRA_ALERT_ID, alertId)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_BODY, body)
        .putExtra(EXTRA_SITE_ID, siteId ?: 0)
        .putExtra(EXTRA_DEVICE_ID, deviceId ?: 0)
        .putExtra(EXTRA_SENT_AT, sentAt)

    companion object {
        const val DEFAULT_TITLE = "警告"

        private const val EXTRA_TYPE = "net.howlrs.sitealert.extra.TYPE"
        private const val EXTRA_ALERT_ID = "net.howlrs.sitealert.extra.ALERT_ID"
        private const val EXTRA_TITLE = "net.howlrs.sitealert.extra.TITLE"
        private const val EXTRA_BODY = "net.howlrs.sitealert.extra.BODY"
        private const val EXTRA_SITE_ID = "net.howlrs.sitealert.extra.SITE_ID"
        private const val EXTRA_DEVICE_ID = "net.howlrs.sitealert.extra.DEVICE_ID"
        private const val EXTRA_SENT_AT = "net.howlrs.sitealert.extra.SENT_AT"

        /**
         * 未知の type は null を返して無視する。alert_id がなければ FCM の messageId で代用する。
         */
        fun fromData(data: Map<String, String>, fallbackId: String?): AlertMessage? {
            val type = Type.entries.firstOrNull { it.wire == data["type"] } ?: return null
            val alertId = data["alert_id"]?.takeIf { it.isNotBlank() } ?: fallbackId ?: return null
            return AlertMessage(
                type = type,
                alertId = alertId,
                title = data["title"]?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE,
                body = data["body"].orEmpty(),
                siteId = data["site_id"]?.toIntOrNull(),
                deviceId = data["device_id"]?.toIntOrNull(),
                sentAt = data["sent_at"]?.takeIf { it.isNotBlank() },
            )
        }

        fun fromIntent(intent: Intent): AlertMessage? {
            val type = Type.entries.firstOrNull { it.wire == intent.getStringExtra(EXTRA_TYPE) } ?: return null
            return AlertMessage(
                type = type,
                alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return null,
                title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE,
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                siteId = intent.getIntExtra(EXTRA_SITE_ID, 0).takeIf { it > 0 },
                deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, 0).takeIf { it > 0 },
                sentAt = intent.getStringExtra(EXTRA_SENT_AT),
            )
        }
    }
}
