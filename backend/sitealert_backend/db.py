"""watch_devices テーブルの読み書き（docs/03 §2, §3.4）。

参考実装として SQLite を使う。既存の監視システムに組み込む時は同じ操作を
その DB（PostgreSQL / MySQL など、sql/ 配下の DDL）で実装する。

1 行が時計 1 台に当たる（D-14）。行の鍵は時計本体の識別子 `watch_id` で、
作業員が入力する端末番号 `device_id` は貸し出し台帳と照らし合わせるための表示用の番号にすぎない。
登録で書き換わるのはその時計自身の行だけなので、別の時計が同じ番号を入力しても
先に登録した時計の行は消えない（通知が届かなくなる時計を作らない）。
"""

from __future__ import annotations

import sqlite3
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS watch_devices (
    watch_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
    site_id          INTEGER      NOT NULL,
    device_id        INTEGER      NOT NULL,
    fcm_token        VARCHAR(512) NOT NULL,
    updated_at       TEXT         NOT NULL,
    app_version      VARCHAR(32),
    token_invalid_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_watch_devices_site ON watch_devices (site_id, device_id);
CREATE INDEX IF NOT EXISTS idx_watch_devices_fcm_token ON watch_devices (fcm_token);
"""

# v0.1 の表（主キーが site_id + device_id）からの移行。時計の識別子は宛先 ID で代用する
MIGRATE_FROM_V1 = """
ALTER TABLE watch_devices RENAME TO watch_devices_v1;
{schema}
INSERT INTO watch_devices
    (watch_id, site_id, device_id, fcm_token, updated_at, app_version, token_invalid_at)
SELECT 'fid:' || fcm_token, site_id, device_id, fcm_token, updated_at, app_version, token_invalid_at
FROM watch_devices_v1;
DROP TABLE watch_devices_v1;
"""


def fallback_watch_id(fcm_token: str) -> str:
    """時計の識別子を送らない旧版アプリ（0.1.x）の行の鍵。"""
    return f"fid:{fcm_token}"


@dataclass(frozen=True)
class Device:
    watch_id: str
    site_id: int
    device_id: int
    fcm_token: str
    updated_at: str
    app_version: str | None
    token_invalid_at: str | None

    @property
    def is_token_valid(self) -> bool:
        return self.token_invalid_at is None


@dataclass(frozen=True)
class Registration:
    updated_at: str
    # 同じ業務日に同じ現場・同じ端末番号で登録した別の時計（台帳との食い違い）
    duplicates: list[Device]


class DeviceRepository:
    def __init__(self, path: str) -> None:
        self._path = path

    def _connect(self) -> sqlite3.Connection:
        if self._path != ":memory:":
            Path(self._path).parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self._path)
        connection.row_factory = sqlite3.Row
        return connection

    def init_schema(self) -> None:
        with closing(self._connect()) as connection, connection:
            columns = [row["name"] for row in connection.execute("PRAGMA table_info(watch_devices)")]
            if columns and "watch_id" not in columns:
                connection.executescript(MIGRATE_FROM_V1.format(schema=SCHEMA))
            else:
                connection.executescript(SCHEMA)

    def register(
        self,
        watch_id: str,
        site_id: int,
        device_id: int,
        fcm_token: str,
        app_version: str | None,
        now: datetime,
        day_start: datetime,
    ) -> Registration:
        """その時計の行だけを UPSERT する。day_start 以降に同じ番号で登録した別の時計を返す。"""
        updated_at = now.isoformat(timespec="seconds")
        with closing(self._connect()) as connection, connection:
            # 同じ宛先 ID が別の鍵で残っていれば消す（旧版アプリからの更新など。D-10）
            connection.execute(
                "DELETE FROM watch_devices WHERE fcm_token = ? AND watch_id <> ?",
                (fcm_token, watch_id),
            )
            connection.execute(
                "INSERT INTO watch_devices"
                " (watch_id, site_id, device_id, fcm_token, updated_at, app_version, token_invalid_at)"
                " VALUES (?, ?, ?, ?, ?, ?, NULL)"
                " ON CONFLICT (watch_id) DO UPDATE SET"
                "   site_id = excluded.site_id,"
                "   device_id = excluded.device_id,"
                "   fcm_token = excluded.fcm_token,"
                "   updated_at = excluded.updated_at,"
                "   app_version = excluded.app_version,"
                "   token_invalid_at = NULL",
                (watch_id, site_id, device_id, fcm_token, updated_at, app_version),
            )
            rows = connection.execute(
                "SELECT * FROM watch_devices WHERE site_id = ? AND device_id = ? AND watch_id <> ?",
                (site_id, device_id, watch_id),
            ).fetchall()
        others = [Device(**dict(row)) for row in rows]
        duplicates = [d for d in others if datetime.fromisoformat(d.updated_at) >= day_start]
        return Registration(updated_at, duplicates)

    def find(self, watch_id: str) -> Device | None:
        with closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT * FROM watch_devices WHERE watch_id = ?", (watch_id,)
            ).fetchone()
        return Device(**dict(row)) if row else None

    def list_site(self, site_id: int, device_id: int | None = None) -> list[Device]:
        """現場の時計（端末番号を指定した場合はその番号の時計）。"""
        query = "SELECT * FROM watch_devices WHERE site_id = ?"
        params: tuple[int, ...] = (site_id,)
        if device_id is not None:
            query += " AND device_id = ?"
            params += (device_id,)
        with closing(self._connect()) as connection:
            rows = connection.execute(query + " ORDER BY device_id, updated_at", params).fetchall()
        return [Device(**dict(row)) for row in rows]

    def mark_token_invalid(self, watch_id: str, fcm_token: str, now: datetime) -> None:
        """送信に使ったトークンのままなら無効にする。送信中に再登録された新しいトークンは消さない。"""
        with closing(self._connect()) as connection, connection:
            connection.execute(
                "UPDATE watch_devices SET token_invalid_at = ? WHERE watch_id = ? AND fcm_token = ?",
                (now.isoformat(timespec="seconds"), watch_id, fcm_token),
            )

    def remove(self, watch_id: str) -> bool:
        """返却・紛失した時計を外す。以後その時計には送らない。"""
        with closing(self._connect()) as connection, connection:
            return connection.execute(
                "DELETE FROM watch_devices WHERE watch_id = ?", (watch_id,)
            ).rowcount > 0

    def list_devices(self) -> list[Device]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT * FROM watch_devices ORDER BY site_id, device_id, updated_at"
            ).fetchall()
        return [Device(**dict(row)) for row in rows]


def mask_token(token: str) -> str:
    """ログ用。トークン全体は残さない（docs/03 §6）。"""
    if len(token) <= 12:
        return "***"
    return f"{token[:6]}...{token[-4:]}"
