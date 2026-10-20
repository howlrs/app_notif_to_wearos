"""登録API（docs/03 §3）。

起動例: uvicorn --factory sitealert_backend.api:app_from_env --host 127.0.0.1 --port 8765
"""

from __future__ import annotations

import logging
import secrets
from datetime import datetime, timedelta, timezone
from typing import Annotated, Callable
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictInt

from .config import Settings
from .db import DeviceRepository, fallback_watch_id, mask_token

logger = logging.getLogger("sitealert.api")

SITE_ID_MAX = 999_999
DEVICE_ID_MAX = 999


class RegisterRequest(BaseModel):
    # 新旧アプリの混在に備え、未知の項目は無視する（R-10）
    model_config = ConfigDict(extra="ignore")

    site_id: Annotated[StrictInt, Field(ge=1, le=SITE_ID_MAX)]
    device_id: Annotated[StrictInt, Field(ge=1, le=DEVICE_ID_MAX)]
    fcm_token: Annotated[str, Field(min_length=1, max_length=512, pattern=r"^\S+$")]
    app_version: Annotated[str | None, Field(max_length=32)] = None
    # 時計本体の識別子（D-14）。アプリが自動で付ける。旧版アプリは送らない
    watch_id: Annotated[str | None, Field(min_length=1, max_length=64, pattern=r"^[0-9A-Za-z_.:-]+$")] = None


class ApiError(Exception):
    def __init__(self, status_code: int, error: str) -> None:
        self.status_code = status_code
        self.error = error


def error_response(status_code: int, error: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"status": "error", "error": error})


def business_day_start(now: datetime, hour: int, tz: ZoneInfo) -> datetime:
    """now を含む業務日の始まり（例: 4 時区切りなら 3 時台は前日扱い）。"""
    local = now.astimezone(tz)
    start = local.replace(hour=hour, minute=0, second=0, microsecond=0)
    return start if local >= start else start - timedelta(days=1)


def create_app(
    repository: DeviceRepository,
    api_key: str,
    clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc).astimezone(),
    *,
    day_start_hour: int = 4,
    tz: str = "Asia/Tokyo",
) -> FastAPI:
    if not api_key:
        raise ValueError("SITEALERT_API_KEY is required")

    zone = ZoneInfo(tz)
    repository.init_schema()
    app = FastAPI(title="Site alert watch registration API")

    def require_api_key(x_api_key: Annotated[str | None, Header()] = None) -> None:
        # 認証方式は D-02 の推奨（共有APIキー＋HTTPS＋登録ログの監視）
        if x_api_key is None or not secrets.compare_digest(x_api_key, api_key):
            raise ApiError(401, "unauthorized")

    @app.exception_handler(ApiError)
    async def handle_api_error(_: Request, exc: ApiError) -> JSONResponse:
        return error_response(exc.status_code, exc.error)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        location = exc.errors()[0].get("loc", ()) if exc.errors() else ()
        field = location[1] if len(location) > 1 and location[0] == "body" else "body"
        return error_response(400, f"invalid_{field}")

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/api/register", dependencies=[Depends(require_api_key)])
    def register(body: RegisterRequest) -> dict[str, object]:
        now = clock()
        watch_id = body.watch_id or fallback_watch_id(body.fcm_token)
        result = repository.register(
            watch_id, body.site_id, body.device_id, body.fcm_token, body.app_version,
            now, business_day_start(now, day_start_hour, zone),
        )
        logger.info(
            "registered watch=%s site_id=%s device_id=%s app_version=%s token=%s",
            mask_token(watch_id), body.site_id, body.device_id, body.app_version, mask_token(body.fcm_token),
        )
        if result.duplicates:
            # 通知はどちらの時計にも届く。台帳と照らし合わせて番号を直してもらう
            logger.warning(
                "duplicate device number site_id=%s device_id=%s watches=%s",
                body.site_id, body.device_id,
                [mask_token(watch_id)] + [mask_token(d.watch_id) for d in result.duplicates],
            )
        return {
            "status": "ok",
            "site_id": body.site_id,
            "device_id": body.device_id,
            "updated_at": result.updated_at,
            "duplicate_count": len(result.duplicates),
        }

    return app


def app_from_env() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    return create_app(
        DeviceRepository(settings.db_path), settings.api_key or "",
        day_start_hour=settings.day_start_hour, tz=settings.timezone,
    )
