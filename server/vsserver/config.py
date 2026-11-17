"""Configuration and device store (shared by the server and manage.py)."""
from __future__ import annotations

import json
import os
import secrets
import socket
import subprocess
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
DATA = BASE / "data"
CONFIG_FILE = DATA / "config.json"
DEVICES_FILE = DATA / "devices.json"


def _detect_lan_cidr() -> str:
    """Best guess of the local /24 (no packet is sent)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ".".join(ip.split(".")[:3]) + ".0/24"
    except Exception:
        return "192.168.0.0/24"


DEFAULTS = {
    "host": "0.0.0.0",
    "port": 8765,
    "model": "large-v3",
    "device": "cuda",
    "compute_type": "float16",
    # Direct peers in these networks may use the simple LAN token (no signing, plain http ok).
    # Loopback is deliberately NOT trusted: a tunnel from a public server shows up as 127.0.0.1.
    "trusted_lan": [],
    # Only proxies listed here may supply X-Forwarded-For for rate limiting.
    "trusted_proxies": [],
    "lan_token": "",
    "public_url": "",          # what the phone should use from outside (your public server's ai-endpoint)
    "lan_url": "",             # e.g. http://192.168.5.20:8765
    "max_upload_mb": 25,
    "max_audio_minutes": 15,
    "clock_skew_seconds": 120,
    "fail_limit": 10,          # failed auth attempts ...
    "fail_window_s": 600,      # ... within this window ...
    "block_s": 600,            # ... block that client for this long
    "queue_timeout_s": 90,
}


def _restrict(path: Path) -> None:
    """Best effort: only the current user may read the data directory (Windows ACL / POSIX mode)."""
    try:
        if os.name == "nt":
            user = os.environ.get("USERNAME", "")
            if user:
                subprocess.run(
                    ["icacls", str(path), "/inheritance:r", "/grant:r", f"{user}:(OI)(CI)F"],
                    capture_output=True, check=False,
                )
        else:
            os.chmod(path, 0o700)
    except Exception:
        pass


def ensure_dirs() -> None:
    first = not DATA.exists()
    DATA.mkdir(parents=True, exist_ok=True)
    if first:
        _restrict(DATA)


def load_config() -> dict:
    ensure_dirs()
    cfg = dict(DEFAULTS)
    if CONFIG_FILE.exists():
        cfg.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
    changed = False
    if not cfg["lan_token"]:
        cfg["lan_token"] = secrets.token_hex(24)
        changed = True
    if not cfg["trusted_lan"]:
        cfg["trusted_lan"] = [_detect_lan_cidr()]
        changed = True
    if changed or not CONFIG_FILE.exists():
        save_config(cfg)
    return cfg


def save_config(cfg: dict) -> None:
    ensure_dirs()
    tmp = CONFIG_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    tmp.replace(CONFIG_FILE)


# ---------------------------------------------------------------- devices

_cache: dict = {"mtime": None, "data": {}}


def load_devices() -> dict:
    """{device_id: {name, secret, created, revoked}} - reloaded when the file changes."""
    ensure_dirs()
    if not DEVICES_FILE.exists():
        return {}
    m = DEVICES_FILE.stat().st_mtime_ns
    if _cache["mtime"] != m:
        _cache["data"] = json.loads(DEVICES_FILE.read_text(encoding="utf-8"))
        _cache["mtime"] = m
    return _cache["data"]


def _save_devices(d: dict) -> None:
    ensure_dirs()
    tmp = DEVICES_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(d, indent=2), encoding="utf-8")
    tmp.replace(DEVICES_FILE)
    _cache["mtime"] = None


def add_device(name: str) -> tuple[str, str]:
    d = dict(load_devices())
    dev_id = "d_" + secrets.token_hex(6)
    secret = secrets.token_hex(32)  # 256-bit
    d[dev_id] = {"name": name, "secret": secret, "created": int(time.time()), "revoked": False}
    _save_devices(d)
    return dev_id, secret


def revoke_device(dev_id: str) -> bool:
    d = dict(load_devices())
    if dev_id not in d:
        return False
    d[dev_id]["revoked"] = True
    _save_devices(d)
    return True
