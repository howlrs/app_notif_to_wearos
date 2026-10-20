# 引き継ぎ資料

作成日: 2026-10-19  
対象: この案件の作業を引き継いで進める開発者  
内容: 2026-10-19 の作業終了時点の状態、次にやること、開発環境の再開手順と注意点

## 1. 現在の状態

| 項目 | 状態 | 場所 |
| --- | --- | --- |
| 設計資料 | v0.3.0。決定事項・実装・検証結果を反映済み（D-07 現場単位の通知、D-14 時計ごとの登録を含む） | `docs/00`〜`06` |
| Wear OS アプリ | 0.2.0 を実装済み。単体テスト 20 件成功。debug ビルドだけ作った（署名付きの release ビルドは未作成） | `wear-app/` |
| 登録 API・送信処理 | 参考実装（Python / FastAPI / SQLite）。テスト 51 件成功。現場単位の送信、時計ごとの登録と番号の重複の検出。PostgreSQL・MySQL の DDL あり | `backend/` |
| 検証 | Wear OS 5 エミュレーターで、Firebase 経由の実送信を使い、登録から警告の停止までを確認した | [05 §3.1](05-implementation-plan.md#31-エミュレーターでの結果2026-10-19) |
| クライアント向け資料 | 画面画像・スライド画像、説明資料（pptx 19 枚・PDF） | `docs/screenshots/`、`docs/presentation/` |
| 実機 | 未調達・未検証 | ― |
| Google Play Console | 未登録（登録料 25 米ドルと本人確認が要るため、本人が手続きする） | ― |
| 既存の監視システムへの組み込み | 未着手。既存システムの構成が分かっていない（D-09） | ― |
| 実行中のプロセス | なし。エミュレーターと登録 API は作業終了時に止めた。登録データ（DB とエミュレーター内のアプリ設定）は残っている | ― |

要決定事項は D-01〜D-14 すべてに推奨案か、ご指示の内容を採用した。未決は D-05・D-12（現場試行の後に判断）、D-08 の機種選定、D-09 の構成確認である（[06 §2](06-risks-and-open-issues.md#2-要決定事項)）。

2026-10-19 の追加変更（D-07・D-14）: 通知は現場単位で、その現場に登録された時計すべてへ送る。DB の行は時計本体の識別子（`watch_id`）ごとに持ち、作業員が入力する端末番号は貸し出し台帳との照合用にした。同じ日に番号を打ち間違えても、先に登録した時計に通知が届かなくなることはない。エミュレーターで、0.1.0 からの更新、重複の警告、現場単位の送信（偽の宛先が 1 台混ざっても他の時計には届くこと）を確かめた。開発用 DB は旧形式から自動で移行済み。

## 2. 次にやること

| 順 | 作業 | 内容 | 参照 |
| --- | --- | --- | --- |
| 1 | クライアントへの報告 | 説明資料で報告し、既存システムの構成（D-09）と対象機種（D-08）を確認する | `docs/presentation/` の 16・17 枚目 |
| 2 | Google Play Console の登録 | 開発者登録 → アプリ `net.howlrs.sitealert` を作成 → 署名付き `.aab` を内部テストに上げる → テスター用アカウントを登録 | [04 §2.2](04-operations-runbook.md#22-google-play-設定) |
| 3 | 実機での確認 | 検証用に 2 台を調達し、PoC 項目をやり直す。特に P-02（長時間待機後の到達）、P-04（画面の表示）、P-09（電池）、P-10（騒音下） | [05 §3](05-implementation-plan.md#3-poc-検証項目) |
| 4 | 登録 API・送信処理の本番化 | 既存システムへ移植し、HTTPS のドメインで公開する。release ビルドの接続先と API キーを設定する（§5）。設置先から `fcm.googleapis.com` に届くことを確かめる（R-21） | [03](03-backend-spec.md) |
| 5 | 結合テスト・キッティング試行・現場試行 | M4〜M6。試行の結果で D-05・D-12 を決める | [05 §2](05-implementation-plan.md#2-マイルストーン) |

本番の FCM 送信は、サービスアカウントの鍵ファイルを作らず、実行環境に割り当てたサービスアカウント（`roles/firebasecloudmessaging.admin`）で認証する。開発時の権限借用（§4）を本番に持ち込まない。

## 3. 開発環境の再開手順

### 3.1 入っているもの

| 対象 | 内容 |
| --- | --- |
| Android SDK | `~/Android/Sdk`。platforms 36・37、build-tools 36.1.0、`system-images;android-34;android-wear;x86_64` |
| AVD | `sitealert_wear5`（`wearos_large_round`、454×454 の丸型）。日本語・東京時間に設定済み。アプリ 0.2.0 を入れて現場 101・端末 05 で登録済み |
| バックエンド | `backend/.venv`（`requirements-dev.txt` を導入済み）、`backend/data/sitealert.db`（エミュレーターの時計 1 台。現場 101・端末 5） |
| 補助スクリプト | `dev/emu.sh`（エミュレーター操作）、`dev/dev_send.py`（送信 CLI の名前解決の回避策）。どちらも開発環境専用 |

### 3.2 起動から送信まで

```bash
REPO=/home/o9oem/workspace/mine/biz/app_notif_to_wearos

# エミュレーター（画面を出す場合は -no-window を外す）
~/Android/Sdk/emulator/emulator -avd sitealert_wear5 -no-window -no-audio -no-snapshot-save \
  -no-boot-anim -gpu swiftshader_indirect -port 5560 &
~/Android/Sdk/platform-tools/adb -s emulator-5560 wait-for-device

# 登録 API（別の端末で）
cd $REPO/backend && set -a && . ./.env && set +a
.venv/bin/uvicorn --factory sitealert_backend.api:app_from_env --host 127.0.0.1 --port 8765

# アプリのテスト・ビルド・インストール
cd $REPO/wear-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
~/Android/Sdk/platform-tools/adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk

# テスト通知を送って状態を見る
source $REPO/dev/emu.sh
send_alert --type test     # 現場 101 の端末番号 5 の時計へ（現場の全台へは dev/dev_send.py send --site 101）
state                      # 振動・アラーム音・通知・AlertService の状態
```

バックエンドのテストは `cd backend && .venv/bin/python -m pytest`。

### 3.3 よく使う操作

| 目的 | コマンド |
| --- | --- |
| 届かない時に FCM の接続を張り直させる | `fcm_reconnect`（`dev/emu.sh`） |
| 省電力状態（Doze）で試す | `adb shell dumpsys battery unplug` の後に `adb shell dumpsys deviceidle force-idle` |
| 画面を消す前に文字盤へ戻す | `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`（文字盤で HOME キーを送るとアプリ一覧が開くため） |
| 画面を撮る | `shot 名前`（`docs/screenshots/raw/` に保存） |
| 日本語・東京時間に戻す | `adb root` の後に `adb shell "setprop persist.sys.locale ja-JP; setprop persist.sys.timezone Asia/Tokyo; setprop ctl.restart zygote"` |

## 4. アカウントと Google Cloud

| 項目 | 内容 |
| --- | --- |
| Google アカウント | `terashima@howlrs.net` |
| gcloud の使い方 | 共通の設定は切り替えない（既定は gonumb）。コマンドごとに `--account=terashima@howlrs.net` を付けるか、`CLOUDSDK_ACTIVE_CONFIG_NAME=howlrs` を指定する。操作前に `gcswitch status` で確認する |
| プロジェクト | `business-484010`（Firebase 追加済み） |
| 有効にした API | firebase、fcm、firebaseinstallations、iam、iamcredentials、cloudresourcemanager |
| Firebase の Android アプリ | `net.howlrs.sitealert`（appId `1:712672174550:android:33dcc179616b1d174e4477`）。設定は `wear-app/app/google-services.json` |
| 送信用サービスアカウント | `fcm-sender@business-484010.iam.gserviceaccount.com`。プロジェクトに `roles/firebasecloudmessaging.admin` を付与。鍵ファイルは作っていない |
| 開発時の送信 | `terashima@howlrs.net` に `fcm-sender` への `roles/iam.serviceAccountTokenCreator` を付け、権限を借りて送る（`backend/.env` の `SITEALERT_FCM_TOKEN_COMMAND`） |

検証を終えて不要になったら、`terashima@howlrs.net` の `serviceAccountTokenCreator` を外す。

## 5. 秘密情報と設定値の置き場所

| ファイル | 中身 | 扱い |
| --- | --- | --- |
| `wear-app/local.properties` | `sdk.dir`、`siteAlert.apiKey`。release 用の接続先は `siteAlert.registerUrl.release` で指定する（未設定なら `https://example.com/api/register`） | 権限 600、Git 管理外 |
| `backend/.env` | `SITEALERT_API_KEY`、`SITEALERT_DB_PATH`、`FIREBASE_PROJECT_ID`、`SITEALERT_FCM_TOKEN_COMMAND`、`SITEALERT_FCM_TTL_SECONDS` | 権限 600、Git 管理外。`SITEALERT_API_KEY` は `siteAlert.apiKey` と同じ値 |
| `backend/data/sitealert.db` | 開発用の端末台帳 | Git 管理外 |
| `wear-app/app/google-services.json` | Firebase の設定（API キーを含む） | Git 管理外。クローン後は自分の Firebase プロジェクトから取得する |

2026-10-21 に公開用の Git 管理を整備した。除外設定はルートの `.gitignore`、`wear-app/.gitignore`、`backend/.gitignore` にある。クローン後の設定は [README](../README.md#開発環境で試す) と `backend/.env.example` を参照する。README には登録・警告受信・検証結果のスライド画像も掲載している。

## 6. この開発環境で分かった注意点

| 事象 | 原因と対応 |
| --- | --- |
| ポート 8000 が使えない | 同じ PC の SurrealDB が使っている。登録 API は 8765 にした（debug ビルドの接続先も 8765） |
| 送信 CLI が `fcm.googleapis.com` に繋がらない | ExpressVPN の DNS がこの名前を解決しない。Google 宛ては VPN 不要との指示を受け、`dev/dev_send.py` でそのプロセス内だけ公開 DNS（DNS over HTTPS）で引いている。VPN・経路・OS の DNS 設定は変えない。本番のコードには入れない |
| 長く放置すると警告が届かない | 開発環境では端末と FCM の接続が切れたままになった。`fcm_reconnect` で接続し直すと、保持中の警告が届く。現場の Wi-Fi で起きるかは実機で測る（R-21） |
| 警告画面が全画面で自動表示されない | Wear OS 5 は全画面通知（FullScreenIntent）を起動しない（R-01）。手首を上げると警告と「確認」が出る作りにしてある。この表示には、通知チャンネル `site_alert_v2` が「割り込む通知」（振動あり）であることが必要。無音・振動なしのチャンネルに変えると、手首を上げても警告が出なくなる |
| 通知許可の画面が英語 | エミュレーターの OS 部品に日本語訳がないため。アプリの問題ではない |
| FCM の宛先の取得方法 | firebase-messaging 25.1.0 以降、`getToken()` / `onNewToken()` は非推奨。アプリは `register()` / `onRegistered()` と Firebase Installations の ID（FID）を使い、送信側は HTTP v1 の `fid` 欄に入れる（R-20）。旧方式に戻さない |
| compileSdk が 37 | core-ktx 1.19.1 と Compose 1.12 が要求するため。targetSdk は 36、minSdk は 33（Wear OS 4 以降） |

## 7. 資料の作り直し

画面やスライドは、次の順に作り直す。

1. エミュレーターで画面を撮る（`shot 名前`）。
2. `docs/screenshots/make_slides.py` で時計画像とスライド画像を作る（Pillow が必要）。
3. `python3 docs/presentation/make_deck.py` で pptx を作る（python-pptx が必要）。
4. PDF は LibreOffice のコンテナ（Gotenberg）で変換する。

```bash
docker run -d --rm --name sitealert-gotenberg -p 127.0.0.1:3456:3000 \
  -v /mnt/c/Windows/Fonts/BIZ-UDGothicR.ttc:/usr/share/fonts/truetype/bizud/BIZ-UDGothicR.ttc:ro \
  -v /mnt/c/Windows/Fonts/BIZ-UDGothicB.ttc:/usr/share/fonts/truetype/bizud/BIZ-UDGothicB.ttc:ro \
  gotenberg/gotenberg:8
cd docs/presentation
curl -f -X POST http://127.0.0.1:3456/forms/libreoffice/convert \
  -F files=@sitealert_poc_report.pptx -o sitealert_poc_report.pdf
docker stop sitealert-gotenberg
```

フォントを渡さないと、PDF の和文が別の字体になる。
