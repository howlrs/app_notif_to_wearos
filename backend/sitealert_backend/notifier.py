"""閾値超過時の通知送信（docs/03 §4）。"""

from __future__ import annotations

import logging
import math
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Callable

from firebase_admin import exceptions, messaging

from .db import Device, DeviceRepository, mask_token

logger = logging.getLogger("sitealert.notifier")

ALERT = "alert"
TEST = "test"

# 再送しても無駄なエラー（トークン自体が使えない）
TOKEN_INVALID_ERRORS = (messaging.UnregisteredError, messaging.SenderIdMismatchError)
# 一時的なエラー。同じ alert_id で指数バックオフ再送する
RETRYABLE_ERRORS = (
    messaging.QuotaExceededError,
    exceptions.UnavailableError,
    exceptions.InternalError,
)


@dataclass(frozen=True)
class NotifyResult:
    """時計 1 台への送信結果。"""

    status: str  # sent / token_invalid / failed
    alert_id: str
    watch_id: str
    device_id: int
    message_id: str | None = None
    attempts: int = 0
    error: str | None = None


@dataclass(frozen=True)
class SiteNotifyResult:
    """現場への送信結果。status は sent（全台が FCM に受け付けられた）/ partial / failed / not_registered。"""

    status: str
    alert_id: str
    results: list[NotifyResult] = field(default_factory=list)


def build_message(
    token: str,
    *,
    message_type: str,
    alert_id: str,
    site_id: int,
    device_id: int,
    title: str,
    body: str,
    sent_at: datetime,
    ttl_seconds: float,
) -> messaging.Message:
    """notification を含めない data メッセージを優先度 high で組み立てる。値はすべて文字列。

    宛先は Firebase Installation ID（FID）。アプリは FID を fcm_token として登録する。
    HTTP v1 の token 欄は非推奨になったため fid 欄で送る。
    """
    return messaging.Message(
        fid=token,
        data={
            "type": message_type,
            "alert_id": alert_id,
            "site_id": str(site_id),
            "device_id": str(device_id),
            "title": title,
            "body": body,
            "sent_at": sent_at.isoformat(timespec="seconds"),
        },
        # timedelta は float を最寄りのマイクロ秒へ丸めるため、切り上げずに残り時間を渡す。
        android=messaging.AndroidConfig(
            priority="high",
            ttl=timedelta(microseconds=math.floor(ttl_seconds * 1_000_000)),
        ),
    )


class Notifier:
    def __init__(
        self,
        repository: DeviceRepository,
        send: Callable[[messaging.Message], str],
        *,
        ttl_seconds: int = 300,
        max_retries: int = 3,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc).astimezone(),
        monotonic_clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._repository = repository
        self._send = send
        self._ttl_seconds = ttl_seconds
        self._max_retries = max_retries
        self._sleep = sleep
        self._clock = clock
        self._monotonic_clock = monotonic_clock

    def notify_site(
        self,
        site_id: int,
        title: str,
        body: str,
        *,
        device_id: int | None = None,
        message_type: str = ALERT,
        alert_id: str | None = None,
    ) -> SiteNotifyResult:
        """現場に登録されている時計すべてへ送る（D-07）。

        device_id を指定するとその端末番号の時計だけに送る（キッティング時のテスト通知用）。
        同じ番号の時計が複数あれば、すべてに送る（D-14）。
        """
        if message_type == ALERT and device_id is not None:
            raise ValueError("alert notifications cannot specify device_id")

        deadline = self._monotonic_clock() + self._ttl_seconds
        alert_id = alert_id or str(uuid.uuid4())
        devices = self._repository.list_site(site_id, device_id)
        if not devices:
            logger.error(
                "not registered site_id=%s device_id=%s alert_id=%s", site_id, device_id, alert_id
            )
            return SiteNotifyResult("not_registered", alert_id)

        sent_at = self._clock()
        results = [
            self._notify_one(device, title, body, message_type, alert_id, sent_at, deadline)
            for device in devices
        ]
        sent = sum(result.status == "sent" for result in results)
        status = "sent" if sent == len(results) else "failed" if sent == 0 else "partial"
        if status != "sent":
            logger.error(
                "site notification %s site_id=%s alert_id=%s sent=%s/%s",
                status, site_id, alert_id, sent, len(results),
            )
        return SiteNotifyResult(status, alert_id, results)

    def _notify_one(
        self,
        device: Device,
        title: str,
        body: str,
        message_type: str,
        alert_id: str,
        sent_at: datetime,
        deadline: float,
    ) -> NotifyResult:
        def result(status: str, **kwargs) -> NotifyResult:
            return NotifyResult(status, alert_id, device.watch_id, device.device_id, **kwargs)

        watch = mask_token(device.watch_id)
        if not device.is_token_valid:
            logger.error(
                "token invalid since %s watch=%s site_id=%s device_id=%s alert_id=%s",
                device.token_invalid_at, watch, device.site_id, device.device_id, alert_id,
            )
            return result("token_invalid")

        attempts = 0
        while True:
            remaining_seconds = deadline - self._monotonic_clock()
            if remaining_seconds <= 0:
                logger.error(
                    "notification expired alert_id=%s watch=%s attempts=%s",
                    alert_id, watch, attempts,
                )
                return result("failed", attempts=attempts, error="ttl_expired")

            message = build_message(
                device.fcm_token,
                message_type=message_type,
                alert_id=alert_id,
                site_id=device.site_id,
                device_id=device.device_id,
                title=title,
                body=body,
                sent_at=sent_at,
                ttl_seconds=remaining_seconds,
            )
            attempts += 1
            try:
                message_id = self._send(message)
            except TOKEN_INVALID_ERRORS as error:
                self._repository.mark_token_invalid(device.watch_id, device.fcm_token, self._clock())
                logger.error(
                    "token rejected watch=%s site_id=%s device_id=%s token=%s code=%s",
                    watch, device.site_id, device.device_id, mask_token(device.fcm_token), error.code,
                )
                return result("token_invalid", attempts=attempts, error=error.code)
            except RETRYABLE_ERRORS as error:
                if attempts > self._max_retries:
                    logger.error(
                        "giving up alert_id=%s watch=%s after %s attempts code=%s",
                        alert_id, watch, attempts, error.code,
                    )
                    return result("failed", attempts=attempts, error=error.code)
                delay = _retry_after_seconds(error) or 2 ** (attempts - 1)
                remaining_seconds = deadline - self._monotonic_clock()
                if delay >= remaining_seconds:
                    logger.error(
                        "retry deadline expired alert_id=%s watch=%s attempts=%s delay=%s remaining=%s",
                        alert_id, watch, attempts, delay, remaining_seconds,
                    )
                    return result("failed", attempts=attempts, error="ttl_expired")
                logger.warning("retrying alert_id=%s watch=%s in %ss code=%s", alert_id, watch, delay, error.code)
                self._sleep(delay)
                continue
            except exceptions.FirebaseError as error:
                logger.error(
                    "send failed alert_id=%s watch=%s code=%s message=%s", alert_id, watch, error.code, error
                )
                return result("failed", attempts=attempts, error=error.code)

            logger.info(
                "sent alert_id=%s type=%s watch=%s site_id=%s device_id=%s message_id=%s attempts=%s",
                alert_id, message_type, watch, device.site_id, device.device_id, message_id, attempts,
            )
            return result("sent", message_id=message_id, attempts=attempts)


def _retry_after_seconds(error: exceptions.FirebaseError) -> float | None:
    response = error.http_response
    value = response.headers.get("Retry-After") if response is not None else None
    try:
        return float(value) if value is not None else None
    except ValueError:
        return None
