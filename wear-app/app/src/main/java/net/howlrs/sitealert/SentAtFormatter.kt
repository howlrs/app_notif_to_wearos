package net.howlrs.sitealert

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 送信時刻の表示。受信が遅れた場合は「◯分前」を添える（docs/02 §5.2）。 */
object SentAtFormatter {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun describe(sentAt: String?, now: Instant, zone: ZoneId): String? {
        val sent = try {
            OffsetDateTime.parse(sentAt ?: return null).toInstant()
        } catch (e: DateTimeParseException) {
            return null
        }
        val label = "${TIME.format(sent.atZone(zone))} 送信"
        val minutes = Duration.between(sent, now).toMinutes()
        return if (minutes >= 1) "$label（${minutes}分前）" else label
    }
}
