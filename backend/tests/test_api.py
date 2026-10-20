from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient

from sitealert_backend.api import business_day_start, create_app
from sitealert_backend.db import DeviceRepository

API_KEY = "test-key"
NOW = datetime(2026, 10, 19, 10, 0, tzinfo=timezone(timedelta(hours=9)))
VALID = {"site_id": 101, "device_id": 5, "fcm_token": "fid-abc_123:XYZ", "watch_id": "3f9a0c1d2e4b5a6f"}


@pytest.fixture
def repo(tmp_path):
    return DeviceRepository(str(tmp_path / "api.db"))


@pytest.fixture
def client(repo):
    return TestClient(create_app(repo, API_KEY, clock=lambda: NOW))


def post(client, body, key=API_KEY):
    headers = {"X-Api-Key": key} if key is not None else {}
    return client.post("/api/register", json=body, headers=headers)


def test_register_success(client, repo):
    response = post(client, VALID | {"app_version": "0.1.0"})

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "site_id": 101,
        "device_id": 5,
        "updated_at": "2026-10-19T10:00:00+09:00",
        "duplicate_count": 0,
    }
    device = repo.find("3f9a0c1d2e4b5a6f")
    assert (device.site_id, device.device_id) == (101, 5)
    assert device.fcm_token == "fid-abc_123:XYZ"
    assert device.app_version == "0.1.0"


def test_same_number_from_another_watch_is_accepted_and_reported(client, repo):
    post(client, VALID)
    response = post(client, VALID | {"watch_id": "other-watch", "fcm_token": "fid-other"})

    assert response.status_code == 200
    assert response.json()["duplicate_count"] == 1
    assert len(repo.list_site(101, 5)) == 2


def test_old_app_without_watch_id_is_keyed_by_token(client, repo):
    body = {k: v for k, v in VALID.items() if k != "watch_id"}
    assert post(client, body).status_code == 200
    assert repo.find("fid:fid-abc_123:XYZ") is not None

    # 同じ時計が新版に更新されて登録し直すと、旧版の行は置き換わる
    assert post(client, VALID).status_code == 200
    assert [d.watch_id for d in repo.list_devices()] == ["3f9a0c1d2e4b5a6f"]


def test_register_is_idempotent(client, repo):
    assert post(client, VALID).status_code == 200
    assert post(client, VALID).status_code == 200
    assert len(repo.list_devices()) == 1


def test_unknown_fields_are_ignored(client):
    assert post(client, VALID | {"future_field": "x"}).status_code == 200


@pytest.mark.parametrize("key", [None, "", "wrong"])
def test_rejects_missing_or_wrong_api_key(client, repo, key):
    response = post(client, VALID, key=key)

    assert response.status_code == 401
    assert response.json() == {"status": "error", "error": "unauthorized"}
    assert repo.list_devices() == []


def test_auth_is_checked_before_body(client):
    assert post(client, {"site_id": "bad"}, key="wrong").status_code == 401


@pytest.mark.parametrize(
    ("override", "error"),
    [
        ({"site_id": "101"}, "invalid_site_id"),
        ({"site_id": 0}, "invalid_site_id"),
        ({"site_id": 1_000_000}, "invalid_site_id"),
        ({"site_id": True}, "invalid_site_id"),
        ({"device_id": 1000}, "invalid_device_id"),
        ({"device_id": None}, "invalid_device_id"),
        ({"fcm_token": ""}, "invalid_fcm_token"),
        ({"fcm_token": "has space"}, "invalid_fcm_token"),
        ({"fcm_token": "x" * 513}, "invalid_fcm_token"),
        ({"app_version": "v" * 33}, "invalid_app_version"),
        ({"watch_id": ""}, "invalid_watch_id"),
        ({"watch_id": "has space"}, "invalid_watch_id"),
        ({"watch_id": "w" * 65}, "invalid_watch_id"),
    ],
)
def test_rejects_invalid_fields(client, override, error):
    response = post(client, VALID | override)

    assert response.status_code == 400
    assert response.json() == {"status": "error", "error": error}


def test_rejects_missing_field(client):
    body = dict(VALID)
    del body["fcm_token"]
    response = post(client, body)

    assert response.status_code == 400
    assert response.json()["error"] == "invalid_fcm_token"


def test_rejects_non_json_body(client):
    response = client.post(
        "/api/register", content="not json", headers={"X-Api-Key": API_KEY, "Content-Type": "application/json"}
    )
    assert response.status_code == 400
    assert response.json()["error"].startswith("invalid_")


def test_requires_api_key_setting(repo):
    with pytest.raises(ValueError):
        create_app(repo, "")


@pytest.mark.parametrize(
    ("now", "expected"),
    [
        (datetime(2026, 10, 19, 10, 0, tzinfo=timezone.utc), datetime(2026, 10, 19, 4, 0)),
        (datetime(2026, 10, 19, 3, 59, tzinfo=ZoneInfo("Asia/Tokyo")), datetime(2026, 10, 18, 4, 0)),
        (datetime(2026, 10, 19, 4, 0, tzinfo=ZoneInfo("Asia/Tokyo")), datetime(2026, 10, 19, 4, 0)),
    ],
)
def test_business_day_starts_at_configured_hour(now, expected):
    tz = ZoneInfo("Asia/Tokyo")
    assert business_day_start(now, 4, tz) == expected.replace(tzinfo=tz)


def test_healthz(client):
    assert client.get("/healthz").json() == {"status": "ok"}
