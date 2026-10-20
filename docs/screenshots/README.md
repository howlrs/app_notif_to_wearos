# 画面キャプチャ・発表用画像

撮影日: 2026-10-19  
環境: Wear OS 5（API 34）エミュレーター、Firebase プロジェクト business-484010 経由の実送信  
用途: クライアント向けの説明資料（pptx 版は [../presentation/](../presentation/README.md)）

| フォルダ | 内容 |
| --- | --- |
| `slides/` | 16:9（1920×1080）のスライド画像。そのまま資料に貼れる |
| `watch/` | 丸く切り抜いた時計画像（背景透過 PNG）。資料のレイアウトに合わせて個別に使う |
| `raw/` | エミュレーターの元画像（454×454） |

## スライド

| ファイル | 内容 |
| --- | --- |
| `slides/01_registration_flow.png` | 端末登録の流れ（通知の許可 → 入力 → 登録完了） |
| `slides/02_alert_flow.png` | 警告の受信から確認まで |
| `slides/03_verification_results.png` | 検証結果と、実機で確認が必要な事項 |

## 個別画像

| ファイル | 画面 |
| --- | --- |
| `01_permission.png` | 初回起動時の通知許可（エミュレーターのため英語表示） |
| `02_register_empty.png` | 端末登録画面（未入力） |
| `03_keypad.png` | 数字テンキー |
| `04_register_filled.png` | 現場 101・端末 5 を入力した状態 |
| `05_registered_standby.png` | 登録完了・待機中 |
| `06_alert_received.png` | 警告の受信（手首を上げた時の表示） |
| `07_alert_actions.png` | 通知の操作（確認 / スマートウォッチで開く / クリア） |
| `08_alert_screen.png` | アプリの警告画面 |
| `09_after_ack.png` | 「確認」後（通常の画面に戻る） |
| `10_test_notification.png` | キッティング時のテスト通知 |
| `11_duplicate_warning.png` | 同じ日に同じ番号の時計が登録済みの時の警告（D-14） |

## 作り直し

`raw/` を撮り直した後、Pillow を入れた Python で `python make_slides.py` を実行する。
フォントは Windows の BIZ UDPゴシック（`/mnt/c/Windows/Fonts/`）を使う。
