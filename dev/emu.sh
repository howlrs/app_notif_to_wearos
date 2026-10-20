# 開発環境専用: エミュレーター操作の補助。`source dev/emu.sh` で読み込む。
# 前提: AVD sitealert_wear5 をポート 5560 で起動済み（docs/07 §3）
ADB="$HOME/Android/Sdk/platform-tools/adb -s emulator-5560"
REPO=/home/o9oem/workspace/mine/biz/app_notif_to_wearos
RAW=$REPO/docs/screenshots/raw

# 画面を docs/screenshots/raw/<名前>.png に保存する
shot() { $ADB exec-out screencap -p > "$RAW/$1.png" && echo "shot $1"; }

# 画面に出ている文字を一覧する
texts() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | sed 's/text=//' | tr '\n' ' '; echo; }

# 指定の文字が出るまで待つ（既定 30 秒）
wait_text() { for i in $(seq 1 ${2:-30}); do texts | grep -q "$1" && return 0; sleep 1; done; return 1; }

# 文字または resource-id が一致する要素の中心座標（input tap 用）
center_of() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml | python3 -I -c "
import sys,re
key=sys.argv[1]
for m in re.finditer(r'<node ([^>]*)>', sys.stdin.read()):
    a=dict(re.findall(r'([\w-]+)=\"([^\"]*)\"', m.group(1)))
    if a.get('text')==key or a.get('resource-id')==key:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',a['bounds'])); print((x1+x2)//2,(y1+y2)//2); break
" "$1"; }

# 振動・アラーム音・通知・AlertService の状態
state() { echo "vibration=$($ADB shell dumpsys vibrator_manager | sed -n '/CurrentVibration/,+2p' | grep -oE 'status = [a-z]+|null' | head -1) alarm_sound=$($ADB shell dumpsys audio | grep -c 'state:started.*usage=USAGE_ALARM') notif=$($ADB shell dumpsys notification --noredact | grep -c 'NotificationRecord.*sitealert') service=$($ADB shell dumpsys activity services net.howlrs.sitealert | grep -c AlertService)"; }

# 止まっていた FCM の接続を張り直させる（長時間放置後に届かない時、docs/06 R-21）
fcm_reconnect() { $ADB shell am broadcast -a com.google.android.intent.action.MCS_HEARTBEAT >/dev/null 2>&1; $ADB shell am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT >/dev/null 2>&1; }

# 現場 101 の端末番号 5 の時計へ送る。例: send_alert --type test / send_alert --type alert --title 警告
send_alert() { (cd "$REPO/backend" && set -a && . ./.env && set +a && timeout 60 .venv/bin/python -I ../dev/dev_send.py send --site 101 --device 5 "$@" 2>/dev/null | tail -1 | cut -c1-70); }
