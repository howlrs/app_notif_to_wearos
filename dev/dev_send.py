"""開発環境専用: 送信 CLI を、FCM の名前解決だけ公開 DNS に切り替えて動かす。

この PC の VPN 側 DNS が fcm.googleapis.com を解決しないため（docs/06 R-21）、
このプロセス内だけ dns.google の DNS-over-HTTPS で引いた IP を使う。
TLS の SNI と証明書の検証はホスト名のままなので、接続先の確認は通常どおり行われる。
VPN・経路・OS の DNS 設定は変えない。本番の送信処理には入れないこと。

使い方（backend/ で .env を読み込んでから）:
  .venv/bin/python -I ../dev/dev_send.py send --site 101 --device 5 --type test
"""
import json
import socket
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

OVERRIDE_HOSTS = {"fcm.googleapis.com"}


def doh_resolve(host: str) -> list[str]:
    with urllib.request.urlopen(f"https://dns.google/resolve?name={host}&type=A", timeout=10) as response:
        answers = json.load(response).get("Answer", [])
    return [a["data"] for a in answers if a.get("type") == 1]


_resolved = {host: doh_resolve(host) for host in OVERRIDE_HOSTS}
_original_getaddrinfo = socket.getaddrinfo


def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    if host in _resolved and _resolved[host]:
        return [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))
            for ip in _resolved[host]
        ]
    return _original_getaddrinfo(host, port, family, type, proto, flags)


socket.getaddrinfo = getaddrinfo

from sitealert_backend.cli import main  # noqa: E402

sys.exit(main(sys.argv[1:]))
