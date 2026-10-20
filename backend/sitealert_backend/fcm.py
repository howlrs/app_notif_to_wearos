"""Firebase Admin SDK の初期化（docs/03 §5）。"""

from __future__ import annotations

import shlex
import subprocess
from datetime import datetime, timedelta, timezone

import firebase_admin
import google.oauth2.credentials
from firebase_admin import credentials


class CommandTokenCredential(credentials.Base):
    """開発用。外部コマンドが出力するアクセストークンで FCM を呼ぶ。

    例: gcloud auth print-access-token --impersonate-service-account=fcm-sender@<project>.iam.gserviceaccount.com
    サービスアカウント鍵を作らずに、送信用サービスアカウントの権限で検証できる。
    本番はサービスアカウントの既定の認証情報（GOOGLE_APPLICATION_CREDENTIALS など）を使う。
    """

    TOKEN_LIFETIME = timedelta(minutes=50)

    def __init__(self, command: str) -> None:
        self._command = shlex.split(command)
        self._credential = google.oauth2.credentials.Credentials(
            token=None, refresh_handler=self._refresh
        )

    def _refresh(self, request, scopes):  # noqa: ARG002 - google-auth が渡す引数
        token = subprocess.run(
            self._command, capture_output=True, text=True, check=True
        ).stdout.strip()
        # google-auth はタイムゾーンなしの UTC で有効期限を比べる
        expiry = datetime.now(timezone.utc).replace(tzinfo=None) + self.TOKEN_LIFETIME
        return token, expiry

    def get_credential(self):
        return self._credential


def init_firebase(project_id: str, token_command: str | None = None) -> firebase_admin.App:
    credential = (
        CommandTokenCredential(token_command) if token_command else credentials.ApplicationDefault()
    )
    return firebase_admin.initialize_app(credential, {"projectId": project_id})
