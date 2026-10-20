package net.howlrs.sitealert

import java.util.Locale

/** 入力値の検証と表示形式（docs/02 §3.2, §3.3、D-01）。 */
object InputRules {
    val SITE_ID_RANGE = 1..999_999
    val DEVICE_ID_RANGE = 1..999

    val SITE_ID_MAX_LENGTH = SITE_ID_RANGE.last.toString().length
    val DEVICE_ID_MAX_LENGTH = DEVICE_ID_RANGE.last.toString().length

    fun parseSiteId(text: String): Int? = parse(text, SITE_ID_RANGE)

    fun parseDeviceId(text: String): Int? = parse(text, DEVICE_ID_RANGE)

    /** 数字以外を捨て、桁数を制限する。先頭のゼロは残し、解釈時に無視する（"05" → 5）。 */
    fun sanitize(text: String, maxLength: Int): String = text.filter { it in '0'..'9' }.take(maxLength)

    /** 端末番号は 2 桁のゼロ埋めで表示する（5 → "05"、100 以上はそのまま）。 */
    fun formatDeviceId(deviceId: Int): String = String.format(Locale.ROOT, "%02d", deviceId)

    private fun parse(text: String, range: IntRange): Int? {
        if (text.isEmpty() || text.length > range.last.toString().length) return null
        if (!text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in range }
    }
}
