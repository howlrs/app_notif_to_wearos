"""管理者用コマンド。テスト送信（docs/03 §7）と登録状況の確認。

  python -m sitealert_backend.cli devices [--site 101]
  python -m sitealert_backend.cli send --site 101 --type alert --title 警告 --body 測定値が閾値を超えました
  python -m sitealert_backend.cli send --site 101 --device 5 --type test   # その端末番号の時計だけ
  python -m sitealert_backend.cli remove --watch <watch_id>                 # 返却・紛失した時計を外す
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections import Counter
from dataclasses import asdict
from functools import partial

from firebase_admin import messaging

from .config import Settings
from .db import DeviceRepository, mask_token
from .fcm import init_firebase
from .notifier import ALERT, TEST, Notifier


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="sitealert")
    commands = parser.add_subparsers(dest="command", required=True)

    devices = commands.add_parser("devices", help="登録済みの時計の一覧（同じ番号の時計には duplicate を付ける）")
    devices.add_argument("--site", type=int)

    remove = commands.add_parser("remove", help="時計を登録から外す（返却・紛失時）")
    remove.add_argument("--watch", required=True, help="devices に表示される watch_id")

    send = commands.add_parser("send", help="現場の時計すべてへ通知を送る")
    send.add_argument("--site", type=int, required=True)
    send.add_argument("--device", type=int, help="この端末番号の時計だけに送る（テスト通知用）")
    send.add_argument("--type", choices=[TEST, ALERT], default=TEST)
    send.add_argument("--title", default="鳴動確認")
    send.add_argument("--body", default="テスト通知です。「確認」を押してください")
    send.add_argument("--alert-id", help="再送時に同じ ID を指定する")
    send.add_argument("--dry-run", action="store_true", help="FCM で形式だけ検証し、端末には届けない")

    args = parser.parse_args(argv)
    if args.command == "send" and args.type == ALERT and args.device is not None:
        parser.error("--device は --type alert では指定できません（test 通知だけに使えます）")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    repository = DeviceRepository(settings.db_path)
    repository.init_schema()

    if args.command == "devices":
        rows = repository.list_site(args.site) if args.site else repository.list_devices()
        numbers = Counter((device.site_id, device.device_id) for device in rows)
        for device in rows:
            row = asdict(device) | {
                # 旧版アプリの行は watch_id に宛先 ID が入るため伏せる（新版から登録し直すと置き換わる）
                "watch_id": mask_token(device.watch_id) if device.watch_id.startswith("fid:") else device.watch_id,
                "fcm_token": mask_token(device.fcm_token),
                "duplicate": numbers[(device.site_id, device.device_id)] > 1,
            }
            print(json.dumps(row, ensure_ascii=False))
        return 0

    if args.command == "remove":
        removed = repository.remove(args.watch)
        print("removed" if removed else "not found")
        return 0 if removed else 1

    if not settings.firebase_project_id:
        print("FIREBASE_PROJECT_ID を設定してください", file=sys.stderr)
        return 2
    init_firebase(settings.firebase_project_id, settings.fcm_token_command)
    notifier = Notifier(
        repository,
        partial(messaging.send, dry_run=args.dry_run),
        ttl_seconds=settings.fcm_ttl_seconds,
    )
    result = notifier.notify_site(
        args.site, args.title, args.body,
        device_id=args.device, message_type=args.type, alert_id=args.alert_id,
    )
    summary = asdict(result) | {
        "results": [asdict(r) | {"watch_id": mask_token(r.watch_id)} for r in result.results]
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if result.status == "sent" else 1


if __name__ == "__main__":
    sys.exit(main())
