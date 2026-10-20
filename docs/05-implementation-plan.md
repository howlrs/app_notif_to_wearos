# 実装計画書

作成日: 2026-10-19  
バージョン: 0.3.0（D-07・D-14 反映）  
元資料: [00-original-direction.md](00-original-direction.md)（「次のアクション」を含む全体）  
対象: 開発者

## 1. 方針

原本は、Wear OS で FCM の高優先度 `data` メッセージを受けて画面点灯・振動・音で知らせる構成を前提にしている。この前提のうち、Wi-Fi のみの単体運用でスリープ中に届くか、FullScreenIntent で画面が点くかは機種と OS バージョンに左右される（[06 R-01, R-02](06-risks-and-open-issues.md#3-技術リスク)）。そのため、本実装の前に実機での PoC を行い、成立を確かめてからアプリとバックエンドを作る。

## 2. マイルストーン

| ID | 名称 | 主な作業 | 成果物 | 完了条件 |
| --- | --- | --- | --- | --- |
| M0 | 決定と準備 | [D-01, D-02, D-03, D-08, D-09, D-13](06-risks-and-open-issues.md#2-要決定事項) を決める。検証用ウォッチ 2 台を調達する。Firebase プロジェクトと Play Console アカウントを作る | 決定事項の記録、検証環境 | PoC に必要な決定と環境が揃っている |
| M1 | PoC | 受信して通知・振動・画面点灯するだけの最小アプリと、`data` メッセージの送信スクリプトを作り、§3 の項目を検証する | PoC 結果報告（項目ごとの合否と測定値） | P-01〜P-05 が合格。不合格があれば方式を見直してから M2 に進む |
| M2 | アプリ実装 | [02-wearos-app-spec.md](02-wearos-app-spec.md) のとおりに実装する | Wear OS アプリ、単体テスト | §5.1 のテストが通り、実機で登録と受信ができる |
| M3 | バックエンド実装 | [03-backend-spec.md](03-backend-spec.md) のとおりにテーブル、登録 API、通知送信処理、テスト送信手段を作る | DDL、API、送信処理、テスト送信スクリプト | §5.2 のテストが通る |
| M4 | 結合テスト | 登録から閾値超過の通知までを実機で通す。異常系（Wi-Fi 切断、トークン無効、番号変更）も確かめる | 結合テスト結果 | §5.3 の項目がすべて合格 |
| M5 | 配信とキッティング試行 | 内部テストに配信し、[04-operations-runbook.md](04-operations-runbook.md) の手順で 2〜3 台をキッティングする | 手順書の改訂版、機種別の画面付き手順 | 手順書だけを見て担当者がキッティングを完了できる |
| M6 | 現場試行 | 1 現場で 2 週間程度運用する | 試行報告（到達率、遅延、電池、作業員の声） | 本格展開の可否と [D-05, D-12](06-risks-and-open-issues.md#2-要決定事項) を判断できる |

### 2.1 進捗（2026-10-19）

| ID | 状態 | 内容 |
| --- | --- | --- |
| M0 | 完了 | D はすべて推奨案で決定。Firebase（business-484010）に Android アプリ `net.howlrs.sitealert` を登録。送信専用サービスアカウント `fcm-sender` を作成。検証は Wear OS 5 エミュレーターで実施（実機は未調達） |
| M1 | エミュレーターで実施 | §3.1 のとおり。実機での再確認が残る |
| M2 | 実装済み | `wear-app/`（0.2.0）。単体テスト 20 件成功 |
| M3 | 参考実装済み | `backend/`（FastAPI ＋ SQLite）。テスト 51 件成功。現場単位の送信（D-07）と時計ごとの登録（D-14）に対応。既存システムへの組み込みは D-09 の確認後 |
| M4 | エミュレーターで一部実施 | 登録 → 送信 → 鳴動 → 確認までを Firebase 経由の実送信で確認 |
| M5〜M6 | 未着手 | Google Play Console の登録（25 ドル・本人確認）と実機の調達が必要 |

## 3. PoC 検証項目

| ID | 検証内容 | 手順 | 合格基準（暫定） | 関連 |
| --- | --- | --- | --- | --- |
| P-01 | スリープ中の受信 | Bluetooth オフ、Wi-Fi のみ、画面オフで 5 分置いてから送信 | `onMessageReceived` が呼ばれ、送信から 10 秒以内に通知が出る | R-02, R-03 |
| P-02 | 長時間スリープ後の受信 | 画面オフで 1 時間以上置いてから送信 | P-01 と同じ | R-02 |
| P-03 | Wi-Fi の維持 | 画面オフで数時間置き、Wi-Fi 接続が切れていないかを記録する | 送信時に Wi-Fi が繋がっている、または数秒で再接続して受信する | R-02 |
| P-04 | 画面点灯 | 画面オフの状態で受信し、`canUseFullScreenIntent()` の値と画面の様子を記録する | 画面が点き、警告画面が出る | R-01 |
| P-05 | 振動 | バックグラウンド・画面オフで受信 | 約 7 秒のパターンどおりに振動する | R-04 |
| P-06 | アラーム音 | 通常時とおやすみモード時に受信 | 通常時に鳴る。おやすみモード時の挙動を記録する | R-07 |
| P-07 | 数字テンキー | `KeyboardType.Number` の入力欄をタップ | 数字だけの入力画面が出る | R-17 |
| P-08 | 配信と自動更新 | 内部テストからインストールし、新版を上げて充電しながら置く | インストールできる。更新までの時間を記録する | R-09, R-10 |
| P-09 | 電池持ち | Wi-Fi 接続のまま 1 勤務（8〜10 時間）装着する | 終業時に残量が 20% 以上 | R-15 |
| P-10 | 騒音下での気付き | 重機の近くなど実際の騒音環境で受信 | 作業員が気付ける | R-18 |
| P-11 | 再起動・更新後の受信 | ウォッチの再起動後、アプリ更新後に送信 | 再登録なしで受信できる | R-12 |
| P-12 | 切断中に送った通知 | Wi-Fi を切った状態で送信し、TTL 内に再接続 | 再接続後に受信する | R-02, D-06 |

PoC の送信には Admin SDK などのスクリプトを使う。Firebase コンソールの通知作成画面は `notification` メッセージを送るため、`data` メッセージの検証には使えない。

### 3.1 エミュレーターでの結果（2026-10-19）

Wear OS 5（API 34）エミュレーター、Firebase 経由の実送信。画面は [screenshots/](screenshots/README.md)、まとめは [screenshots/slides/03_verification_results.png](screenshots/slides/03_verification_results.png)。

| ID | 結果 | 観測内容 |
| --- | --- | --- |
| P-01 | 合格 | 画面消灯中に受信し、`AlertService` が鳴動を開始 |
| P-02 | 要実機確認 | 開発環境（VPN 経由）では長時間アイドル後に FCM 接続が切れたままになり、接続し直すまで届かなかった（R-21） |
| P-03 | 要実機確認 | エミュレーターでは Wi-Fi の省電力挙動を再現できない |
| P-04 | 不合格（代替あり） | FullScreenIntent は起動しない（R-01）。画面を点けると警告と「確認」ボタンが表示される |
| P-05 | 合格 | 「1 秒振動＋0.5 秒休止」の繰り返し（`USAGE_ALARM`）。60 秒で停止、「確認」で停止 |
| P-06 | 一部合格 | `USAGE_ALARM` で再生されることを確認。おやすみモード時の挙動は未確認 |
| P-07 | 合格 | 数字だけのテンキー（Gboard）が出る |
| P-08 | 未実施 | Google Play Console 未登録 |
| P-09〜P-10 | 未実施 | 実機が必要 |
| P-11 | 一部合格 | アプリ更新（上書きインストール）後も登録を保ったまま受信 |
| P-12 | 合格 | 送信時に届かなかった警告が、FCM 接続の回復後に TTL 内で届いた |
| 追加 | 合格 | 省電力（Doze）を強制した状態で受信。計測した回では送信から約 1 秒で鳴動開始 |

## 4. リポジトリ構成案

```text
app_notif_to_wearos/
├── README.md
├── docs/                         # 本資料一式
├── wear-app/                     # Android Studio プロジェクト（実装済み）
│   ├── local.properties          # SDK パス・登録 API のキーと接続先（Git 管理外）
│   ├── app/
│   │   ├── google-services.json  # Firebase の設定（公開リポジトリにする場合は管理外にする）
│   │   └── src/
│   │       ├── main/java/net/howlrs/sitealert/   # 画面、受信、鳴動、登録
│   │       ├── debug/            # 開発用 API への平文 HTTP 許可
│   │       └── test/             # 単体テスト
│   └── gradle/libs.versions.toml # 依存ライブラリの版
└── backend/                      # 参考実装（既存システムへの移植元）
    ├── .env                      # 開発用の設定（Git 管理外）
    ├── sitealert_backend/        # 登録 API（api.py）、送信（notifier.py / fcm.py）、管理コマンド（cli.py）
    ├── sql/                      # PostgreSQL / MySQL の DDL
    └── tests/                    # pytest
```

`backend/` は既存システムの構成が分かった時点で、既存リポジトリ側に置くかを決める（[D-09](06-risks-and-open-issues.md#2-要決定事項)）。

## 5. テスト方針

### 5.1 アプリ

| 対象 | 内容 | 方法 |
| --- | --- | --- |
| 入力チェック | 空欄、数字以外、範囲外、先頭ゼロ | 単体テスト |
| 表示形式 | `device_id = 5` → `05`、`100` → `100` | 単体テスト |
| 保存 | 保存した値が再起動後に読めること | 単体テスト（Robolectric） |
| 重複判定 | 同じ `alert_id` を 2 回受けても通知は 1 回 | 単体テスト |
| 登録処理 | 応答コードごとの状態遷移（[02 §4.4](02-wearos-app-spec.md#44-応答の扱い)） | 単体テスト（HTTP をモック） |
| 受信・鳴動 | 振動、音、画面点灯 | 実機（PoC 項目を再実施） |

### 5.2 バックエンド

| 対象 | 内容 | 方法 |
| --- | --- | --- |
| 登録 API の検証 | 型・範囲・長さの不備で 400 を返す | 単体テスト |
| UPSERT | 時計ごとの新規登録と上書き、同じ番号の別の時計の行を残すこと、業務日内の重複の検出、同じトークンの別の時計 ID の行の削除 | DB を使ったテスト |
| 送信ペイロード | `data` のみ、値がすべて文字列、`priority = high`、TTL 付き | 単体テスト。FCM の `dry_run` で形式を検証 |
| エラー処理 | FCM のエラー種別ごとの無効化・再送 | 単体テスト（FCM をモック） |

### 5.3 結合テスト

| No. | シナリオ | 期待結果 |
| --- | --- | --- |
| 1 | キッティング手順どおりに登録し、テスト通知を送る | 画面点灯、振動、音、警告画面 |
| 2 | 閾値超過を発生させる | その現場に登録した時計すべてに通知が届き、他の現場の時計には届かない |
| 3 | 端末番号を 5 → 6 に変更し、5 宛てと 6 宛てに送る | 5 宛ては「未登録」として記録され、6 宛ては届く |
| 4 | アプリをアンインストールしてから送る | `token_invalid_at` が設定され、管理者へ知らせが出る |
| 5 | Wi-Fi を切って登録し、後で繋ぐ | 繋いだ後に自動で登録が完了する |
| 6 | 新旧 2 つのバージョンのアプリから登録する | どちらも成功する |
| 7 | 同じ日に 2 台の時計へ同じ現場・同じ番号を登録し、現場へ送る | 両方に届く。後から登録した時計に「同じ番号の時計あり」と出る。一覧で両方に `duplicate: true` が付く |

## 6. 実装指示プロンプト（改訂版）

注: 本プロンプトは実装前に作ったもの。実装済みのコードがあるため、今後の改修では `wear-app/` を正とする。宛先の取得は FID 方式（`register()` / `onRegistered()`）に変わっている（R-20）。

原本の「次のアクション」のプロンプト例を、本資料一式に合わせて改訂したもの。原文は [00-original-direction.md](00-original-direction.md) に残している。主な変更点は、振動時間を仕様本文の約 7 秒に揃えたこと、`onNewToken()` での再登録、通知権限、FullScreenIntent が使えない場合の代替動作、単体動作の宣言を加えたことである。

```text
Wear OS 向けの Kotlin（Jetpack Compose for Wear OS）アプリを作ります。
仕様は docs/02-wearos-app-spec.md、API とペイロードは docs/03-backend-spec.md に従ってください。

1. MainActivity
   - site_id (Int) と device_id (Int) を KeyboardType.Number の入力欄で入力し、「登録 / 更新」ボタンで確定する。
   - 値は SharedPreferences に保存する。登録済みなら画面中央に大きく「現場: 101」「端末: 05」「（待機中）」を表示し、
     入力画面へは「設定変更」の長押しでのみ遷移する。
   - 登録時とアプリ起動時に、FirebaseMessaging.getInstance().token で取得したトークンと一緒に
     {"site_id":101,"device_id":5,"fcm_token":"..."} を https://example.com/api/register へ POST する。
     POST は WorkManager（NetworkType.CONNECTED、失敗時は指数バックオフで再試行）で行う。
   - 初回起動時に POST_NOTIFICATIONS の許可を求める。
     NotificationManager.canUseFullScreenIntent() が false なら「全画面通知: 不可」と警告を表示する。

2. MyFirebaseMessagingService
   - onMessageReceived で data メッセージ（type, alert_id, site_id, device_id, title, body, sent_at）を受け取る。
   - alert_id で重複を除き、IMPORTANCE_HIGH の通知チャンネル site_alert_v1（アラーム音、USAGE_ALARM）で通知を出す。
   - setFullScreenIntent で AlertActivity を起動して画面を点灯させる。
   - Vibrator で「1 秒振動＋0.5 秒休止」を 5 回（合計約 7 秒）、USAGE_ALARM で振動させる。
   - onNewToken では、保存値があれば同じ WorkManager の登録処理を実行する。

3. AlertActivity
   - showWhenLocked / turnScreenOn を有効にし、警告メッセージと送信時刻、大きな「確認」ボタンを表示する。
   - 「確認」で振動と音を止め、通知を消す。

4. AndroidManifest.xml
   - uses-feature android.hardware.type.watch、meta-data com.google.android.wearable.standalone=true。
   - 権限: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, VIBRATE, WAKE_LOCK, USE_FULL_SCREEN_INTENT。
   - MyFirebaseMessagingService を com.google.firebase.MESSAGING_EVENT で登録する。

入力チェック、表示形式、保存、alert_id の重複判定には単体テストも書いてください。
```
