package net.howlrs.sitealert

/** 受信済み alert_id の直近の履歴。FCM の重複配信で同じ警告を 2 回鳴らさないために使う。 */
object AlertIdHistory {
    const val CAPACITY = 20
    private const val SEPARATOR = "\n"

    fun decode(serialized: String?): List<String> =
        serialized?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    /** 未受信なら追加後の履歴を返す。受信済みなら null を返す。 */
    fun add(history: List<String>, alertId: String): List<String>? {
        if (alertId in history) return null
        return (history + alertId).takeLast(CAPACITY)
    }

    fun encode(history: List<String>): String = history.joinToString(SEPARATOR)
}
