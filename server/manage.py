"""
Manage VoiceScribe server credentials.

  python manage.py init                      create config, print the LAN token + trusted network
  python manage.py set --public-url URL --lan-url URL [--lan-net CIDR]
  python manage.py add-device "my phone"   create a device and print its pairing code (shown ONCE)
  python manage.py list
  python manage.py revoke d_xxxxxxxxxxxx
  python manage.py rotate-lan-token          new simple-auth token for scripts on the trusted LAN
  python manage.py show-lan-token
"""
from __future__ import annotations

import argparse
import base64
import json
import secrets
import time

from vsserver import config


def pairing_code(cfg: dict, name: str, dev_id: str, secret: str) -> str:
    payload = {
        "v": 1,
        "name": name,
        "id": dev_id,
        "secret": secret,
        "remote": cfg.get("public_url", ""),
        "lan": cfg.get("lan_url", ""),
    }
    raw = json.dumps(payload, separators=(",", ":")).encode()
    return "vs1:" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    s = sub.add_parser("set")
    s.add_argument("--public-url")
    s.add_argument("--lan-url")
    s.add_argument("--lan-net", action="append", help="trusted LAN CIDR (repeatable)")
    s.add_argument("--port", type=int)
    a = sub.add_parser("add-device")
    a.add_argument("name")
    sub.add_parser("list")
    r = sub.add_parser("revoke")
    r.add_argument("device_id")
    sub.add_parser("rotate-lan-token")
    sub.add_parser("show-lan-token")
    args = p.parse_args()

    cfg = config.load_config()

    if args.cmd == "init":
        print(f"data dir      : {config.DATA}")
        print(f"trusted LAN   : {', '.join(cfg['trusted_lan'])}")
        print(f"port          : {cfg['port']}")
        print("Next: manage.py set --lan-url http://<pc-lan-ip>:%d --public-url https://<your-public-server>/<path>" % cfg["port"])
    elif args.cmd == "set":
        if args.public_url is not None:
            cfg["public_url"] = args.public_url.rstrip("/")
        if args.lan_url is not None:
            cfg["lan_url"] = args.lan_url.rstrip("/")
        if args.lan_net:
            cfg["trusted_lan"] = args.lan_net
        if args.port:
            cfg["port"] = args.port
        config.save_config(cfg)
        print("saved")
    elif args.cmd == "add-device":
        dev_id, secret = config.add_device(args.name)
        print(f"device {dev_id} ({args.name}) created.\n")
        print("Pairing code (contains the device secret - shown only now, treat like a password):\n")
        print(pairing_code(cfg, args.name, dev_id, secret))
        print("\nPaste it into the app (Hoch -> Kopplungscode), then delete the message you used to transfer it.")
        if not cfg.get("public_url"):
            print("note: no public_url set yet - the phone will only work on the LAN until you set it and re-pair.")
    elif args.cmd == "list":
        for dev_id, d in config.load_devices().items():
            state = "REVOKED" if d.get("revoked") else "active"
            print(f"{dev_id}  {d['name']:<24} {state:<8} created {time.strftime('%Y-%m-%d', time.localtime(d['created']))}")
    elif args.cmd == "revoke":
        print("revoked" if config.revoke_device(args.device_id) else "unknown device id")
    elif args.cmd == "rotate-lan-token":
        cfg["lan_token"] = secrets.token_hex(24)
        config.save_config(cfg)
        print("LAN token rotated.")
    elif args.cmd == "show-lan-token":
        print("LAN token (for curl/scripts from inside the trusted LAN only; the app does not use it):")
        print(cfg["lan_token"])


if __name__ == "__main__":
    main()
