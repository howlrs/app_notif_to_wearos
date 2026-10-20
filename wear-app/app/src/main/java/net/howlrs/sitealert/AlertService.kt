package net.howlrs.sitealert

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 警告の鳴動を受け持つ短時間のフォアグラウンドサービス（docs/02 §5.4〜5.6、D-04）。
 *
 * 「確認」されるまで振動とアラーム音を繰り返し、[MAX_ALERT_MILLIS] で止める。
 * 止めた後も通知は静かに残し、作業員が後から内容を確認できるようにする。
 */
class AlertService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterLimit = Runnable { finishAlerting(keepNotification = true) }
    private val session = AlertSession()
    private var lastStartId = 0
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val acknowledgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val alertId = intent.getStringExtra(AlertController.EXTRA_ALERT_ID)
            val notificationId = intent.getIntExtra(AlertController.EXTRA_NOTIFICATION_ID, 0)
            when (intent.action) {
                AlertController.ACTION_ACKNOWLEDGE -> if (session.acknowledge(alertId, notificationId)) {
                    finishAlerting(keepNotification = false)
                }
                AlertController.ACTION_DISMISS_REPLACED -> session.dismiss(alertId, notificationId)?.let { message ->
                    finishAlerting(keepNotification = false, message = message)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            acknowledgeReceiver,
            IntentFilter().apply {
                addAction(AlertController.ACTION_ACKNOWLEDGE)
                addAction(AlertController.ACTION_DISMISS_REPLACED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val message = intent?.let(AlertMessage::fromIntent)
        if (message == null) {
            if (session.currentMessage == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // FGS が握る旧通知は cancel を受け付けないことがあるため、先にサービス側で除去する。
        if (session.currentMessage != null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        AlertNotificationCoordinator(AlertNotificationState(this)) { notificationId ->
            AlertNotifier.cancel(this, notificationId)
        }.start(message)
        session.start(message)
        val notification = AlertNotifier.build(this, message, AlertNotifier.CHANNEL_ID, alerting = true)
        ServiceCompat.startForeground(
            this,
            message.notificationId,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                0
            },
        )
        startAlerting()
        handler.removeCallbacks(stopAfterLimit)
        handler.postDelayed(stopAfterLimit, MAX_ALERT_MILLIS)
        Log.i(TAG, "alerting started id=${message.alertId}")
        return START_NOT_STICKY
    }

    private fun startAlerting() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SiteAlert:alert")
                .apply { acquire(MAX_ALERT_MILLIS + 5_000) }
        }
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(AlertNotifier.VIBRATION_CYCLE, 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
        if (ringtone?.isPlaying != true) {
            ringtone = RingtoneManager.getRingtone(this, AlertNotifier.alarmSoundUri())?.apply {
                audioAttributes = AlertNotifier.ALARM_AUDIO_ATTRIBUTES
                isLooping = true
                play()
            }
        }
    }

    private fun stopSoundAndVibration() {
        handler.removeCallbacks(stopAfterLimit)
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        ringtone?.stop()
        ringtone = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun finishAlerting(keepNotification: Boolean, message: AlertMessage? = session.currentMessage) {
        stopSoundAndVibration()
        val keepCurrent = keepNotification && message != null && AlertNotificationState(this).isCurrent(message)
        // 残す通知は切り離す。REMOVE 後の同じ ID への再掲示は、遅延した削除に巻き込まれ得る。
        ServiceCompat.stopForeground(
            this,
            if (keepCurrent) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        if (keepCurrent) {
            androidx.core.app.NotificationManagerCompat.from(this).notify(
                message.notificationId,
                AlertNotifier.build(this, message, AlertNotifier.CHANNEL_ID, alerting = false),
            )
        }
        Log.i(TAG, "alerting finished keepNotification=$keepNotification")
        stopSelf(lastStartId)
    }

    /** shortService の制限時間（約 3 分）に達した時。通常は [MAX_ALERT_MILLIS] で先に止まる。 */
    override fun onTimeout(startId: Int) = finishAlerting(keepNotification = true)

    override fun onTimeout(startId: Int, fgsType: Int) = finishAlerting(keepNotification = true)

    override fun onDestroy() {
        unregisterReceiver(acknowledgeReceiver)
        stopSoundAndVibration()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SiteAlert"
        const val MAX_ALERT_MILLIS = 60_000L

        fun intent(context: Context, message: AlertMessage): Intent =
            message.putInto(Intent(context, AlertService::class.java))
    }
}
