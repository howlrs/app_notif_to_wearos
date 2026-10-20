package net.howlrs.sitealert

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** 端末内に保存する登録情報（docs/02 §4.1）。アプリ更新後も残る。 */
class DevicePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    enum class Status { OK, PENDING, FAILED }

    /** duplicateCount は、同じ日に同じ番号で登録した別の時計の数（D-14）。0 以外なら台帳との照合を促す */
    data class Snapshot(val siteId: Int?, val deviceId: Int?, val status: Status, val duplicateCount: Int = 0) {
        val isRegistered: Boolean get() = siteId != null && deviceId != null
    }

    val siteId: Int? get() = prefs.getInt(KEY_SITE_ID, 0).takeIf { it > 0 }
    val deviceId: Int? get() = prefs.getInt(KEY_DEVICE_ID, 0).takeIf { it > 0 }
    val isRegistered: Boolean get() = siteId != null && deviceId != null

    /** 最後に登録APIへ送れた FCM の宛先 ID（FID） */
    val lastRegisteredToken: String? get() = prefs.getString(KEY_LAST_TOKEN, null)

    fun snapshot(): Snapshot = Snapshot(
        siteId = siteId,
        deviceId = deviceId,
        status = runCatching { Status.valueOf(prefs.getString(KEY_STATUS, null) ?: "") }
            .getOrDefault(Status.PENDING),
        duplicateCount = prefs.getInt(KEY_DUPLICATE_COUNT, 0),
    )

    /**
     * 時計本体の識別子が取れない時の代わり。アプリ内で一度だけ作る。
     * 入れ直すと変わるが、サーバーは同じ宛先 ID の古い行を消すので重複は残らない。
     */
    val fallbackWatchId: String
        @Synchronized get() = prefs.getString(KEY_FALLBACK_WATCH_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit(commit = true) { putString(KEY_FALLBACK_WATCH_ID, it) } }

    /** 入力値を保存し、登録が終わるまで未同期扱いにする。 */
    fun saveIds(siteId: Int, deviceId: Int) = prefs.edit(commit = true) {
        putInt(KEY_SITE_ID, siteId)
        putInt(KEY_DEVICE_ID, deviceId)
        putString(KEY_STATUS, Status.PENDING.name)
        putInt(KEY_DUPLICATE_COUNT, 0)
    }

    fun markRegistered(token: String, atMillis: Long, duplicateCount: Int) = prefs.edit {
        putString(KEY_LAST_TOKEN, token)
        putLong(KEY_LAST_REGISTERED_AT, atMillis)
        putString(KEY_STATUS, Status.OK.name)
        putInt(KEY_DUPLICATE_COUNT, duplicateCount)
    }

    fun markFailed() = prefs.edit { putString(KEY_STATUS, Status.FAILED.name) }

    /** 未受信の alert_id なら記録して true を返す。受信済みなら false。 */
    @Synchronized
    fun markAlertReceived(alertId: String): Boolean {
        val history = AlertIdHistory.decode(prefs.getString(KEY_RECENT_ALERT_IDS, null))
        val updated = AlertIdHistory.add(history, alertId) ?: return false
        prefs.edit(commit = true) { putString(KEY_RECENT_ALERT_IDS, AlertIdHistory.encode(updated)) }
        return true
    }

    fun snapshots(): Flow<Snapshot> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val FILE_NAME = "device_registration"
        const val KEY_SITE_ID = "site_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_TOKEN = "last_registered_token"
        const val KEY_LAST_REGISTERED_AT = "last_registered_at"
        const val KEY_STATUS = "registration_status"
        const val KEY_RECENT_ALERT_IDS = "recent_alert_ids"
        const val KEY_DUPLICATE_COUNT = "duplicate_count"
        const val KEY_FALLBACK_WATCH_ID = "fallback_watch_id"
    }
}
