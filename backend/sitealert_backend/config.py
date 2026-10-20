"""環境変数からの設定読み込み。"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_path: str
    api_key: str | None
    firebase_project_id: str | None
    fcm_token_command: str | None
    fcm_ttl_seconds: int
    # 業務日の区切り。これ以降に同じ番号で登録した別の時計を「同じ日の重複」として知らせる（D-14）
    day_start_hour: int
    timezone: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            db_path=os.environ.get("SITEALERT_DB_PATH", "data/sitealert.db"),
            api_key=os.environ.get("SITEALERT_API_KEY") or None,
            firebase_project_id=os.environ.get("FIREBASE_PROJECT_ID") or None,
            # 開発用。指定するとこのコマンドの出力をアクセストークンとして使う（例: gcloud のサービスアカウント借用）
            fcm_token_command=os.environ.get("SITEALERT_FCM_TOKEN_COMMAND") or None,
            fcm_ttl_seconds=int(os.environ.get("SITEALERT_FCM_TTL_SECONDS", "300")),
            day_start_hour=int(os.environ.get("SITEALERT_DAY_START_HOUR", "4")),
            timezone=os.environ.get("SITEALERT_TIMEZONE", "Asia/Tokyo"),
        )
