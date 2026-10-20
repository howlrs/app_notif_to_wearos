import pytest

from sitealert_backend import cli


def test_alert_device_filter_is_rejected_before_settings_or_database(monkeypatch):
    monkeypatch.setattr(cli.Settings, "from_env", lambda: pytest.fail("settings must not be read"))
    monkeypatch.setattr(cli, "DeviceRepository", lambda *_: pytest.fail("database must not be opened"))
    monkeypatch.setattr(cli, "init_firebase", lambda *_: pytest.fail("FCM must not be initialized"))

    with pytest.raises(SystemExit) as error:
        cli.main(["send", "--site", "101", "--device", "5", "--type", "alert"])

    assert error.value.code == 2
