package net.howlrs.sitealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 「確認」操作で鳴動を止め、通知を消す（docs/02 §5.7）。 */
object AlertController {
    internal const val ACTION_ACKNOWLEDGE = "net.howlrs.sitealert.ACKNOWLEDGE"
    internal const val ACTION_DISMISS_REPLACED = "net.howlrs.sitealert.DISMISS_REPLACED"
    internal const val EXTRA_ALERT_ID = "net.howlrs.sitealert.extra.ACK_ALERT_ID"
    internal const val EXTRA_NOTIFICATION_ID = "net.howlrs.sitealert.extra.ACK_NOTIFICATION_ID"

    fun acknowledge(context: Context, alertId: String?, notificationId: Int) {
        if (alertId.isNullOrBlank() || notificationId <= 0) return
        // 起動中のサービスだけに届ける。60秒で停止済みの場合、新たなサービスは起動しない。
        context.sendBroadcast(
            Intent(ACTION_ACKNOWLEDGE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ALERT_ID, alertId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
        )
        AlertNotificationCoordinator(AlertNotificationState(context.applicationContext)) { id ->
            AlertNotifier.cancel(context, id)
        }.acknowledge(alertId, notificationId)
    }

    /** 新着警報への機械的な置換で、旧FGSをID照合付きで停止させる。 */
    fun dismissReplaced(context: Context, replaced: AlertNotificationRef) {
        context.sendBroadcast(
            Intent(ACTION_DISMISS_REPLACED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ALERT_ID, replaced.alertId)
                .putExtra(EXTRA_NOTIFICATION_ID, replaced.notificationId),
        )
    }
}

/** 通知の「確認」ボタンから呼ばれる。 */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        AlertController.acknowledge(
            context,
            intent.getStringExtra(AlertController.EXTRA_ALERT_ID),
            intent.getIntExtra(AlertController.EXTRA_NOTIFICATION_ID, 0),
        )
}
