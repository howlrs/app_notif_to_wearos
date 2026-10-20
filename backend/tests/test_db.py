import sqlite3
from datetime import datetime, timedelta, timezone

import pytest

from sitealert_backend.db import DeviceRepository, mask_token

JST = timezone(timedelta(hours=9))
NOW = datetime(2026, 10, 19, 10, 0, tzinfo=JST)
DAY_START = datetime(2026, 10, 19, 4, 0, tzinfo=JST)


@pytest.fixture
def repo(tmp_path):
    repository = DeviceRepository(str(tmp_path / "test.db"))
    repository.init_schema()
    return repository


def register(repo, watch, site, device, token, at=NOW, version=None):
    return repo.register(watch, site, device, token, version, at, DAY_START)


def test_register_inserts_and_upserts_the_same_watch(repo):
    register(repo, "watch-a", 101, 5, "fid-a", version="0.1.0")
    register(repo, "watch-a", 101, 5, "fid-b", at=NOW + timedelta(minutes=1), version="0.2.0")

    device = repo.find("watch-a")
    assert device.fcm_token == "fid-b"
    assert device.app_version == "0.2.0"
    assert device.updated_at == "2026-10-19T10:01:00+09:00"
    assert len(repo.list_devices()) == 1


def test_same_watch_can_change_its_number_and_site(repo):
    register(repo, "watch-a", 101, 5, "fid-a")
    register(repo, "watch-a", 202, 6, "fid-a")  # 翌日の貸し出しで別の現場・番号になった

    device = repo.find("watch-a")
    assert (device.site_id, device.device_id) == (202, 6)
    assert repo.list_site(101) == []


def test_same_number_on_another_watch_keeps_both_and_reports_duplicate(repo):
    register(repo, "watch-a", 101, 5, "fid-a", at=NOW - timedelta(hours=1))
    result = register(repo, "watch-b", 101, 5, "fid-b")  # 同じ日に番号を打ち間違えた

    assert [d.watch_id for d in result.duplicates] == ["watch-a"]
    # 先に登録した時計の行は残る（通知が届かなくならない）
    assert [d.watch_id for d in repo.list_site(101, 5)] == ["watch-a", "watch-b"]


def test_same_number_from_an_earlier_business_day_is_not_a_duplicate(repo):
    register(repo, "watch-a", 101, 5, "fid-a", at=DAY_START - timedelta(minutes=1))
    result = register(repo, "watch-b", 101, 5, "fid-b")

    assert result.duplicates == []
    assert len(repo.list_site(101, 5)) == 2


def test_other_sites_and_numbers_are_not_duplicates(repo):
    register(repo, "watch-a", 101, 5, "fid-a")
    register(repo, "watch-b", 202, 5, "fid-b")

    assert register(repo, "watch-c", 101, 6, "fid-c").duplicates == []
    assert [d.watch_id for d in repo.list_site(101)] == ["watch-a", "watch-c"]


def test_same_token_under_another_watch_id_is_removed(repo):
    register(repo, "fid:fid-a", 101, 5, "fid-a")  # 旧版アプリの行
    register(repo, "watch-a", 101, 5, "fid-a")  # 同じ時計が新版から登録し直した

    assert [d.watch_id for d in repo.list_devices()] == ["watch-a"]


def test_invalid_mark_only_applies_to_same_token_and_is_reset_by_reregistration(repo):
    register(repo, "watch-a", 101, 5, "fid-a")

    repo.mark_token_invalid("watch-a", "fid-old", NOW)
    assert repo.find("watch-a").is_token_valid

    repo.mark_token_invalid("watch-a", "fid-a", NOW)
    assert not repo.find("watch-a").is_token_valid

    register(repo, "watch-a", 101, 5, "fid-a")
    assert repo.find("watch-a").is_token_valid


def test_remove(repo):
    register(repo, "watch-a", 101, 5, "fid-a")

    assert repo.remove("watch-a")
    assert not repo.remove("watch-a")
    assert repo.list_devices() == []


def test_migrates_v1_table(tmp_path):
    path = str(tmp_path / "v1.db")
    with sqlite3.connect(path) as connection:
        connection.executescript(
            "CREATE TABLE watch_devices (site_id INTEGER NOT NULL, device_id INTEGER NOT NULL,"
            " fcm_token VARCHAR(512) NOT NULL, updated_at TEXT NOT NULL, app_version VARCHAR(32),"
            " token_invalid_at TEXT, PRIMARY KEY (site_id, device_id));"
            "INSERT INTO watch_devices VALUES (101, 5, 'fid-a', '2026-10-19T09:00:00+09:00', '0.1.0', NULL);"
        )
    repo = DeviceRepository(path)
    repo.init_schema()
    repo.init_schema()  # 2 回目は何もしない

    device = repo.find("fid:fid-a")
    assert (device.site_id, device.device_id, device.app_version) == (101, 5, "0.1.0")


def test_mask_token_hides_the_middle():
    assert mask_token("abcdefghijklmnopqrstuvwxyz") == "abcdef...wxyz"
    assert mask_token("short") == "***"
