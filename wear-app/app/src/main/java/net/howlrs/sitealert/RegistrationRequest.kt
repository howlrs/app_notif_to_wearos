package net.howlrs.sitealert

/**
 * 登録APIへ送る JSON（docs/03 §3.2）。
 * watchId は時計本体の識別子で、サーバーはこれを行の鍵にする。deviceId は作業員が入力した端末番号（D-14）。
 */
data class RegistrationRequest(
    val siteId: Int,
    val deviceId: Int,
    val fcmToken: String,
    val watchId: String,
    val appVersion: String?,
) {
    fun toJson(): String = buildString {
        append("{\"site_id\":").append(siteId)
        append(",\"device_id\":").append(deviceId)
        append(",\"fcm_token\":").append(quote(fcmToken))
        append(",\"watch_id\":").append(quote(watchId))
        if (appVersion != null) append(",\"app_version\":").append(quote(appVersion))
        append('}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}

/** 登録APIの応答本文の読み取り（docs/03 §3.3）。 */
object RegistrationResponse {
    private val DUPLICATE_COUNT = Regex("\"duplicate_count\"\\s*:\\s*(\\d+)")

    /** 同じ日に同じ現場・番号で登録した別の時計の数。項目がなければ 0（旧版の API）。 */
    fun duplicateCount(body: String): Int =
        DUPLICATE_COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** 登録APIの応答コードごとの扱い（docs/02 §4.4）。 */
enum class RegistrationOutcome {
    /** 2xx。待機中にする */
    SUCCESS,

    /** 入力値や認証の不備。自動再試行しない */
    REJECTED,

    /** 通信障害やサーバー側の一時的な問題。自動で再試行する */
    RETRY;

    companion object {
        fun fromStatus(code: Int): RegistrationOutcome = when (code) {
            in 200..299 -> SUCCESS
            408, 429 -> RETRY
            in 400..499 -> REJECTED
            else -> RETRY
        }
    }
}
