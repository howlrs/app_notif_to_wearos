package net.howlrs.sitealert

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * 警告の鳴動を受け持つ短時間のフォアグラウンドサービス（docs/02 §5.4〜5.6、D-04）。
 *
 * 「確認」されるまで振動とアラーム音を繰り返し、[MAX_ALERT_MILLIS] で止める。
 * 止めた後も通知は静かに残し、作業員が後から内容を確認できるようにする。
 */
class AlertService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterLimit = Runnable { finishAlerting(keepNotification = true) }
    private var currentMessage: AlertMessage? = null
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.let(AlertMessage::fromIntent)
        if (message == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentMessage = message
        val notification = AlertNotifier.build(this, message, AlertNotifier.CHANNEL_ID, alerting = true)
        ServiceCompat.startForeground(
            this,
            AlertNotifier.NOTIFICATION_ID,
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

    private fun finishAlerting(keepNotification: Boolean) {
        stopSoundAndVibration()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val message = currentMessage
        if (keepNotification && message != null) {
            getSystemService(NotificationManager::class.java).notify(
                AlertNotifier.NOTIFICATION_ID,
                AlertNotifier.build(this, message, AlertNotifier.CHANNEL_ID, alerting = false),
            )
        }
        Log.i(TAG, "alerting finished keepNotification=$keepNotification")
        stopSelf()
    }

    /** shortService の制限時間（約 3 分）に達した時。通常は [MAX_ALERT_MILLIS] で先に止まる。 */
    override fun onTimeout(startId: Int) = finishAlerting(keepNotification = true)

    override fun onTimeout(startId: Int, fgsType: Int) = finishAlerting(keepNotification = true)

    override fun onDestroy() {
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
