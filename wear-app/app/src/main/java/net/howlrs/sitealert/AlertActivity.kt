package net.howlrs.sitealert

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.time.Instant
import java.time.ZoneId

/**
 * 全画面通知から起動する警告画面（docs/02 §5.7）。
 * 画面の点灯とロック画面上への表示はマニフェストの turnScreenOn / showWhenLocked で行う。
 */
class AlertActivity : ComponentActivity() {
    private var message by mutableStateOf<AlertMessage?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
        setContent {
            MaterialTheme {
                AlertScreen(message) {
                    AlertController.acknowledge(this)
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        message = AlertMessage.fromIntent(intent)
        Log.i(TAG, "alert screen shown id=${message?.alertId}")
    }

    private companion object {
        const val TAG = "SiteAlert"
    }
}

/**
 * 警告画面。スクロールさせず、「確認」ボタンを常に画面下部に大きく出す。
 * 本文が長い場合は省略する（全文は通知にも出ている）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AlertScreen(message: AlertMessage?, onAcknowledge: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val details = listOfNotNull(
        SentAtFormatter.describe(message?.sentAt, Instant.now(), ZoneId.systemDefault()),
        message?.siteId?.let { siteId ->
            message.deviceId?.let { "現場 $siteId / 端末 ${InputRules.formatDeviceId(it)}" }
        },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.errorContainer)
            .semantics { testTagsAsResourceId = true }
            .padding(start = 28.dp, end = 28.dp, top = 36.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(
                message?.displayTitle ?: AlertMessage.DEFAULT_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onErrorContainer,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("alert_title"),
            )
            message?.body?.takeIf { it.isNotEmpty() }?.let { body ->
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onErrorContainer,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("alert_body"),
                )
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onErrorContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(
            onClick = onAcknowledge,
            colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp).testTag("ack_button"),
        ) {
            Text(
                "確認",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
