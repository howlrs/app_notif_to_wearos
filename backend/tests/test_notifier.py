from datetime import datetime, timedelta, timezone

import pytest
from firebase_admin import exceptions, messaging

from sitealert_backend.db import DeviceRepository
from sitealert_backend.notifier import Notifier

NOW = datetime(2026, 10, 19, 10, 15, tzinfo=timezone(timedelta(hours=9)))
DAY_START = datetime(2026, 10, 19, 4, 0, tzinfo=timezone(timedelta(hours=9)))


class FakeSender:
    def __init__(self, outcomes=None):
        self.outcomes = list(outcomes or [])
        self.messages = []

    def __call__(self, message):
        self.messages.append(message)
        outcome = self.outcomes.pop(0) if self.outcomes else "projects/p/messages/1"
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


@pytest.fixture
def repo(tmp_path):
    repository = DeviceRepository(str(tmp_path / "n.db"))
    repository.init_schema()
    repository.register("watch-a", 101, 5, "fid-a", "0.1.0", NOW, DAY_START)
    return repository


def add_watch(repo, watch, site, device, token):
    repo.register(watch, site, device, token, None, NOW, DAY_START)


def make(repo, sender, sleeps=None):
    return Notifier(
        repo, sender, ttl_seconds=300, max_retries=3,
        sleep=(sleeps.append if sleeps is not None else lambda _: None),
        clock=lambda: NOW,
    )


def test_sends_data_only_high_priority_message_to_fid(repo):
    sender = FakeSender()
    result = make(repo, sender).notify_site(101, "警告", "閾値超過", alert_id="a-1")

    assert result.status == "sent"
    assert result.results[0].message_id == "projects/p/messages/1"
    assert result.results[0].watch_id == "watch-a"
    message = sender.messages[0]
    assert message.fid == "fid-a"
    assert message.token is None
    assert message.notification is None
    assert message.android.priority == "high"
    assert message.android.ttl == timedelta(seconds=300)
    assert message.data == {
        "type": "alert",
        "alert_id": "a-1",
        "site_id": "101",
        "device_id": "5",
        "title": "警告",
        "body": "閾値超過",
        "sent_at": "2026-10-19T10:15:00+09:00",
    }
    assert all(isinstance(value, str) for value in message.data.values())


def test_generates_alert_id_and_supports_test_type(repo):
    sender = FakeSender()
    result = make(repo, sender).notify_site(101, "鳴動確認", "テスト", device_id=5, message_type="test")

    assert sender.messages[0].data["type"] == "test"
    assert sender.messages[0].data["alert_id"] == result.alert_id
    assert len(result.alert_id) == 36


def test_sends_to_every_watch_of_the_site_with_its_own_number(repo):
    add_watch(repo, "watch-b", 101, 6, "fid-b")
    add_watch(repo, "watch-c", 202, 5, "fid-c")
    sender = FakeSender()
    result = make(repo, sender).notify_site(101, "警告", "x", alert_id="a-1")

    assert result.status == "sent"
    assert [(m.fid, m.data["device_id"], m.data["alert_id"]) for m in sender.messages] == [
        ("fid-a", "5", "a-1"),
        ("fid-b", "6", "a-1"),
    ]


def test_watches_sharing_a_number_all_receive(repo):
    add_watch(repo, "watch-b", 101, 5, "fid-b")  # 同じ番号を打ち間違えた時計
    sender = FakeSender()
    result = make(repo, sender).notify_site(101, "鳴動確認", "x", device_id=5, message_type="test")

    assert result.status == "sent"
    assert sorted(m.fid for m in sender.messages) == ["fid-a", "fid-b"]


def test_one_failing_watch_does_not_stop_the_others(repo):
    add_watch(repo, "watch-b", 101, 6, "fid-b")
    sender = FakeSender([messaging.UnregisteredError("gone"), "projects/p/messages/2"])
    result = make(repo, sender).notify_site(101, "警告", "x")

    assert result.status == "partial"
    assert [r.status for r in result.results] == ["token_invalid", "sent"]


@pytest.mark.parametrize(("site", "device"), [(102, None), (101, 6)])
def test_unregistered_site_or_number_is_not_sent(repo, site, device):
    sender = FakeSender()
    result = make(repo, sender).notify_site(site, "警告", "x", device_id=device)

    assert result.status == "not_registered"
    assert sender.messages == []


@pytest.mark.parametrize(
    "error",
    [
        messaging.UnregisteredError("gone"),
        messaging.SenderIdMismatchError("other project"),
    ],
)
def test_token_errors_mark_device_invalid_without_retry(repo, error):
    sender = FakeSender([error])
    result = make(repo, sender).notify_site(101, "警告", "x")

    assert result.status == "failed"
    assert result.results[0].status == "token_invalid"
    assert len(sender.messages) == 1
    assert not repo.find("watch-a").is_token_valid
    # 無効になった時計へは送らない
    again = FakeSender()
    assert make(repo, again).notify_site(101, "警告", "x").results[0].status == "token_invalid"
    assert again.messages == []


def test_transient_errors_are_retried_with_same_alert_id(repo):
    sleeps = []
    sender = FakeSender([
        exceptions.UnavailableError("down"),
        exceptions.InternalError("oops"),
        "projects/p/messages/9",
    ])
    result = make(repo, sender, sleeps).notify_site(101, "警告", "x", alert_id="a-1")

    assert result.status == "sent"
    assert result.results[0].attempts == 3
    assert sleeps == [1, 2]
    assert {m.data["alert_id"] for m in sender.messages} == {"a-1"}


def test_gives_up_after_max_retries(repo):
    sleeps = []
    sender = FakeSender([messaging.QuotaExceededError("slow down")] * 10)
    result = make(repo, sender, sleeps).notify_site(101, "警告", "x")

    assert result.status == "failed"
    assert result.results[0].attempts == 4
    assert sleeps == [1, 2, 4]


def test_invalid_argument_is_not_retried(repo):
    sender = FakeSender([exceptions.InvalidArgumentError("bad payload")])
    result = make(repo, sender).notify_site(101, "警告", "x")

    assert result.status == "failed"
    assert len(sender.messages) == 1
    assert repo.find("watch-a").is_token_valid
