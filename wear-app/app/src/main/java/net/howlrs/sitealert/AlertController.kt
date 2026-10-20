package net.howlrs.sitealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** 「確認」操作で鳴動を止め、通知を消す（docs/02 §5.7）。 */
object AlertController {
    fun acknowledge(context: Context) {
        context.stopService(Intent(context, AlertService::class.java))
        NotificationManagerCompat.from(context).cancel(AlertNotifier.NOTIFICATION_ID)
    }
}

/** 通知の「確認」ボタンから呼ばれる。 */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AlertController.acknowledge(context)
}
