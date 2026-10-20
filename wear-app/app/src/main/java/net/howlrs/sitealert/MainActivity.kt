package net.howlrs.sitealert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.work.WorkInfo

/** 入力・登録・現在値表示の画面（docs/02 §3）。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = DevicePrefs(this)
        // 起動時の再登録（トークン自動更新対策）
        if (savedInstanceState == null && prefs.isRegistered) RegistrationWorker.enqueue(this)
        setContent {
            MaterialTheme {
                MainScreen(prefs)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MainScreen(prefs: DevicePrefs) {
    val context = LocalContext.current
    val snapshot by remember { prefs.snapshots() }.collectAsStateWithLifecycle(prefs.snapshot())
    val workInfos by remember { RegistrationWorker.workInfos(context) }.collectAsStateWithLifecycle(emptyList())
    val sending = workInfos.any { it.state == WorkInfo.State.RUNNING }
    var editing by rememberSaveable { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var fullScreenAllowed by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = it
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        fullScreenAllowed = AlertNotifier.canUseFullScreenIntent(context)
        onPauseOrDispose { }
    }

    AppScaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        if (editing || !snapshot.isRegistered) {
            EditScreen(
                initialSiteId = snapshot.siteId,
                initialDeviceId = snapshot.deviceId,
                canCancel = snapshot.isRegistered,
                onSubmit = { siteId, deviceId ->
                    prefs.saveIds(siteId, deviceId)
                    RegistrationWorker.enqueue(context)
                    editing = false
                },
                onCancel = { editing = false },
            )
        } else {
            RegisteredScreen(
                snapshot = snapshot,
                sending = sending,
                notificationsEnabled = notificationsEnabled,
                fullScreenAllowed = fullScreenAllowed,
                onEdit = { editing = true },
                onRetry = { RegistrationWorker.enqueue(context) },
            )
        }
    }
}

@Composable
private fun RegisteredScreen(
    snapshot: DevicePrefs.Snapshot,
    sending: Boolean,
    notificationsEnabled: Boolean,
    fullScreenAllowed: Boolean,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
    val siteId = snapshot.siteId ?: return
    val deviceId = snapshot.deviceId ?: return
    val status = when {
        sending -> "（送信中）"
        snapshot.status == DevicePrefs.Status.OK -> "（待機中）"
        else -> "（未同期）"
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text("現場: $siteId", style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("site_label"))
            }
            item {
                Text(
                    "端末: ${InputRules.formatDeviceId(deviceId)}",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("device_label"),
                )
            }
            item { Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("status_label")) }
            if (!notificationsEnabled) item { WarningText("通知: 不可（設定で許可）") }
            if (!fullScreenAllowed) item { WarningText("全画面通知: 不可") }
            if (snapshot.duplicateCount > 0 && snapshot.status == DevicePrefs.Status.OK) {
                // 通知は届くが、台帳の番号と食い違っている可能性が高い（D-14）
                item { WarningText("同じ番号の時計あり\n台帳を確認") }
            }
            if (snapshot.status == DevicePrefs.Status.FAILED && !sending) {
                item {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("retry_button")) {
                        Text("再試行")
                    }
                }
            }
            item {
                // 触れただけで値が変わらないよう、長押しでのみ入力画面へ移る
                FilledTonalButton(
                    onClick = {},
                    onLongClick = onEdit,
                    onLongClickLabel = "設定変更",
                    modifier = Modifier.fillMaxWidth().testTag("edit_button"),
                ) {
                    Text("設定変更（長押し）")
                }
            }
        }
    }
}

@Composable
private fun EditScreen(
    initialSiteId: Int?,
    initialDeviceId: Int?,
    canCancel: Boolean,
    onSubmit: (siteId: Int, deviceId: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var siteText by rememberSaveable { mutableStateOf(initialSiteId?.toString().orEmpty()) }
    var deviceText by rememberSaveable { mutableStateOf(initialDeviceId?.toString().orEmpty()) }
    val siteId = InputRules.parseSiteId(siteText)
    val deviceId = InputRules.parseDeviceId(deviceText)
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        // 丸い画面の下端に沿うボタン。リストの最後に置くと下端で欠けるため
        edgeButton = {
            EdgeButton(
                onClick = { if (siteId != null && deviceId != null) onSubmit(siteId, deviceId) },
                enabled = siteId != null && deviceId != null,
                modifier = Modifier.testTag("register_button"),
            ) {
                Text("登録 / 更新")
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("端末登録") } }
            item {
                NumberField("現場", siteText, InputRules.SITE_ID_MAX_LENGTH, "site_id_field") { siteText = it }
            }
            item {
                NumberField("端末", deviceText, InputRules.DEVICE_ID_MAX_LENGTH, "device_id_field") { deviceText = it }
            }
            if (canCancel) {
                item {
                    FilledTonalButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("キャンセル")
                    }
                }
            }
        }
    }
}

/** 数字テンキーだけを出す入力欄（KeyboardType.Number）。 */
@Composable
private fun NumberField(label: String, value: String, maxLength: Int, tag: String, onValueChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(InputRules.sanitize(it, maxLength)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().testTag(tag),
        decorationBox = { innerTextField ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                innerTextField()
            }
        },
    )
}

@Composable
private fun WarningText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("warning_label"),
    )
}
