package net.howlrs.sitealert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.FutureTask

/**
 * 警告通知の組み立て（docs/02 §5.3）。
 *
 * 音と振動の繰り返しは AlertService が受け持つ。[CHANNEL_ID] は音なし・短い振動ありにする。
 * 振動がないと OS が通知を「割り込みなし」と扱い、Wear OS が画面を点けて通知を出さないため。
 * フォアグラウンドサービスを起動できなかった時だけ、OS が鳴らす [FALLBACK_CHANNEL_ID] を使う。
 */
object AlertNotifier {
    /** 設定を変える時は版を上げ、古い ID を [RETIRED_CHANNEL_IDS] に移す（R-05） */
    const val CHANNEL_ID = "site_alert_v2"
    private val RETIRED_CHANNEL_IDS = listOf("site_alert_v1")
    const val FALLBACK_CHANNEL_ID = "site_alert_fallback_v1"

    /** 1 秒振動＋0.5 秒休止。AlertService はこれを確認まで繰り返す（D-04） */
    val VIBRATION_CYCLE = longArrayOf(0, 1000, 500)

    /** 予備チャンネル用。1 秒振動＋0.5 秒休止を 5 回（約 7 秒） */
    val FALLBACK_VIBRATION = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000)

    val ALARM_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val main = NotificationChannel(CHANNEL_ID, "現場アラート", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "閾値超過などの警告。繰り返しの音と振動はアプリが鳴らす"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION_CYCLE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val fallback = NotificationChannel(
            FALLBACK_CHANNEL_ID, "現場アラート（予備）", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "アプリが鳴動できない時に OS が鳴らす警告"
            setSound(alarmSoundUri(), ALARM_AUDIO_ATTRIBUTES)
            enableVibration(true)
            vibrationPattern = FALLBACK_VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        RETIRED_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannels(listOf(main, fallback))
    }

    fun alarmSoundUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /**
     * @param alerting true なら全画面表示を伴う警告として出す。false は鳴動を終えた後の静かな再掲示。
     */
    fun build(context: Context, message: AlertMessage, channelId: String, alerting: Boolean): Notification {
        val openAlert = PendingIntent.getActivity(
            context,
            0,
            message.putInto(Intent(context, AlertActivity::class.java))
                .setData(alertUri(message.alertId, message.notificationId, "open"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acknowledge = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AckReceiver::class.java)
                .setData(alertUri(message.alertId, message.notificationId, "ack"))
                .putExtra(AlertController.EXTRA_ALERT_ID, message.alertId)
                .putExtra(AlertController.EXTRA_NOTIFICATION_ID, message.notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(message.displayTitle)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAlert)
            .addAction(R.drawable.ic_alert, "確認", acknowledge)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alerting)
            .setSilent(!alerting)
            .apply { if (alerting) setFullScreenIntent(openAlert, true) }
            .build()
    }

    /** フォアグラウンドサービスを起動できない時の予備経路。音と振動は OS が 1 回鳴らす。 */
    @SuppressLint("MissingPermission")
    fun postFallback(context: Context, message: AlertMessage) {
        val appContext = context.applicationContext
        runOnMainThread {
            val coordinator = AlertNotificationCoordinator(AlertNotificationState(appContext)) { notificationId ->
                cancel(appContext, notificationId)
            }
            val replaced = coordinator.start(message)
            if (replaced != null) AlertController.dismissReplaced(appContext, replaced)
            val manager = NotificationManagerCompat.from(appContext)
            if (manager.areNotificationsEnabled()) {
                manager.notify(message.notificationId, build(appContext, message, FALLBACK_CHANNEL_ID, alerting = true))
            }
        }
    }

    /** 確認・タイムアウトと直列化し、FCM のコールバックを通知掲示より先に完了させない。 */
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            val task = FutureTask<Unit> { action() }
            check(Handler(Looper.getMainLooper()).post(task)) { "main looper is unavailable" }
            task.get()
        }
    }

    fun cancel(context: Context, notificationId: Int) =
        NotificationManagerCompat.from(context).cancel(notificationId)

    // extras と hashCode だけでは PendingIntent の識別にならない。完全な ID を URI の一要素にする。
    private fun alertUri(alertId: String, notificationId: Int, operation: String): Uri =
        Uri.Builder().scheme("sitealert").authority("alert")
            .appendPath(alertId).appendPath(notificationId.toString()).appendPath(operation).build()

    /** Android 14 以降は通話・アラーム用途のアプリにだけ許可される（R-01） */
    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
