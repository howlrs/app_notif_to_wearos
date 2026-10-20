package net.howlrs.sitealert

import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM の data メッセージを受けて警告を鳴らし、宛先 ID が変わった時は再登録する（docs/02 §5）。 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val receivedMessage = AlertMessage.fromData(remoteMessage.data, remoteMessage.messageId)
        if (receivedMessage == null) {
            Log.w(TAG, "ignored message keys=${remoteMessage.data.keys}")
            return
        }
        val prefs = DevicePrefs(this)
        if (!prefs.markAlertReceived(receivedMessage.alertId)) {
            Log.i(TAG, "duplicate alert ignored id=${receivedMessage.alertId}")
            return
        }
        val message = AlertNotificationState(this).assign(receivedMessage)
        Log.i(
            TAG,
            "alert received id=${message.alertId} type=${message.type.wire} " +
                "priority=${remoteMessage.priority} originalPriority=${remoteMessage.originalPriority}",
        )
        // 宛先が保存値と違っても安全側に倒して鳴らし、食い違いだけ記録する
        if (message.siteId != prefs.siteId || message.deviceId != prefs.deviceId) {
            Log.w(TAG, "destination mismatch message=${message.siteId}/${message.deviceId} saved=${prefs.siteId}/${prefs.deviceId}")
        }
        try {
            ContextCompat.startForegroundService(this, AlertService.intent(this, message))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException を含む。OS が鳴らす予備チャンネルで知らせる
            Log.w(TAG, "foreground service not allowed, using fallback channel", e)
            AlertNotifier.postFallback(this, message)
        }
    }

    /** 登録完了時と FID の変更時に呼ばれる。変わっていれば登録APIへ送り直す（旧 onNewToken の役割）。 */
    override fun onRegistered(installationId: String) {
        val prefs = DevicePrefs(this)
        if (prefs.isRegistered && installationId != prefs.lastRegisteredToken) {
            Log.i(TAG, "FCM registration changed, re-registering")
            RegistrationWorker.enqueue(this, ExistingWorkPolicy.KEEP)
        }
    }

    private companion object {
        const val TAG = "SiteAlert"
    }
}
