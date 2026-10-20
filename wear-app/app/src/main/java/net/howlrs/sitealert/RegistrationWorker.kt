package net.howlrs.sitealert

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 保存済みの site_id / device_id と FCM の宛先 ID を登録APIへ POST する（docs/02 §4.3）。
 * 宛先 ID は Firebase Installation ID（FID）。旧来の登録トークン（getToken）は SDK で非推奨になった。
 * 通信できるまで待ち、失敗時は指数バックオフで再試行する。
 */
class RegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = DevicePrefs(applicationContext)
        val siteId = prefs.siteId ?: return Result.success()
        val deviceId = prefs.deviceId ?: return Result.success()

        val token = try {
            // FCM への登録を済ませてから、その宛先となる FID を送る
            FirebaseMessaging.getInstance().register().await()
            FirebaseInstallations.getInstance().id.await()
        } catch (e: Exception) {
            Log.w(TAG, "FCM registration unavailable", e)
            prefs.markFailed()
            return Result.retry()
        }

        val request = RegistrationRequest(siteId, deviceId, token, watchId(prefs), BuildConfig.VERSION_NAME)
        val (status, body) = try {
            withContext(Dispatchers.IO) { post(request.toJson()) }
        } catch (e: IOException) {
            Log.w(TAG, "register failed: ${e.message}")
            prefs.markFailed()
            return Result.retry()
        }

        val outcome = RegistrationOutcome.fromStatus(status)
        Log.i(TAG, "register site=$siteId device=$deviceId status=$status outcome=$outcome")
        // 送信中に番号が変更された場合は、その変更で登録し直される
        if (prefs.siteId != siteId || prefs.deviceId != deviceId) return Result.success()
        return when (outcome) {
            RegistrationOutcome.SUCCESS -> {
                val duplicates = RegistrationResponse.duplicateCount(body)
                if (duplicates > 0) Log.w(TAG, "device number shared with $duplicates other watch(es) today")
                prefs.markRegistered(token, System.currentTimeMillis(), duplicates)
                Result.success()
            }
            RegistrationOutcome.REJECTED -> {
                prefs.markFailed()
                Result.failure()
            }
            RegistrationOutcome.RETRY -> {
                prefs.markFailed()
                Result.retry()
            }
        }
    }

    /** 時計本体の識別子（D-14）。アプリを入れ直しても変わらず、時計を初期化すると変わる。 */
    @SuppressLint("HardwareIds") // 広告には使わず、登録の鍵としてだけ使う
    private fun watchId(prefs: DevicePrefs): String =
        Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: prefs.fallbackWatchId

    /** 応答コードと本文を返す。本文は成功時だけ読む。 */
    private fun post(json: String): Pair<Int, String> {
        val connection = URL(BuildConfig.REGISTER_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Api-Key", BuildConfig.REGISTER_API_KEY)
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "SiteAlert"
        private const val UNIQUE_WORK_NAME = "register"
        private const val TIMEOUT_MILLIS = 10_000

        /**
         * 利用者の操作と起動時は [ExistingWorkPolicy.REPLACE] でやり直す。
         * onRegistered からは [ExistingWorkPolicy.KEEP] にし、実行中の登録（その中の register() が
         * onRegistered を呼ぶ）を打ち切って同じ登録を繰り返さないようにする。
         */
        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<RegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }

        fun workInfos(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}
