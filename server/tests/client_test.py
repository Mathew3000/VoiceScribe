"""
End-to-end check against a running server:  python tests/client_test.py <audio-file> [base-url]
Creates a temporary device, exercises the auth rules, revokes the device again.
"""
import hashlib
import hmac
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from vsserver import config  # noqa: E402

audio = Path(sys.argv[1]).read_bytes()
base = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8765"

dev_id, secret = config.add_device("test-client")
cfg = config.load_config()


def sign(method, path, query, ts, nonce, body, key=None):
    msg = f"v1\n{method}\n{path}\n{query}\n{ts}\n{nonce}\n{hashlib.sha256(body).hexdigest()}".encode()
    return hmac.new(bytes.fromhex(key or secret), msg, hashlib.sha256).hexdigest()


def call(method, path, query="", body=b"", headers=None, signed=True, ts=None, nonce=None, key=None, dev=None):
    h = dict(headers or {})
    if signed:
        ts = ts if ts is not None else int(time.time())
        nonce = nonce or secrets.token_hex(16)
        h.update({
            "X-Device-Id": dev or dev_id, "X-Timestamp": str(ts), "X-Nonce": nonce,
            "X-Signature": sign(method, path, query, ts, nonce, body, key),
        })
    url = base + path + (("?" + query) if query else "")
    req = urllib.request.Request(url, data=body if method == "POST" else None, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:80]


results = []


def check(name, got, want):
    ok = got == want
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + f"{name}: got {got}, want {want}")


try:
    s, j = call("GET", "/v1/ping")
    check("signed ping", s, 200)

    t = time.time()
    s, j = call("POST", "/v1/transcribe", "language=de", audio)
    check("signed transcribe", s, 200)
    if s == 200:
        print(f"      {j['duration']}s audio, server {j['elapsed']}s, round trip {time.time() - t:.1f}s")
        print("      TEXT:", j["text"].encode("ascii", "replace").decode())

    n = secrets.token_hex(16)
    ts = int(time.time())
    check("first use of nonce", call("GET", "/v1/ping", ts=ts, nonce=n)[0], 200)
    check("replay of same nonce", call("GET", "/v1/ping", ts=ts, nonce=n)[0], 401)
    check("old timestamp", call("GET", "/v1/ping", ts=int(time.time()) - 3600)[0], 401)
    check("wrong secret", call("GET", "/v1/ping", key=secrets.token_hex(32))[0], 401)
    check("unknown device", call("GET", "/v1/ping", dev="d_doesnotexist")[0], 401)
    check("no credentials", call("GET", "/v1/ping", signed=False)[0], 401)

    # tampering: sign one body, send another
    ts = int(time.time())
    nonce = secrets.token_hex(16)
    sig = sign("POST", "/v1/transcribe", "language=de", ts, nonce, audio)
    h = {"X-Device-Id": dev_id, "X-Timestamp": str(ts), "X-Nonce": nonce, "X-Signature": sig}
    check("tampered body", call("POST", "/v1/transcribe", "language=de", audio[:-10] + b"x" * 10, h, signed=False)[0], 401)
    check("tampered query", call("POST", "/v1/transcribe", "language=en", audio, h, signed=False)[0], 401)

    # server proves it knows the secret (the app checks this before sending audio to a LAN address)
    ts = int(time.time())
    nonce = secrets.token_hex(16)
    req = urllib.request.Request(base + "/v1/ping", headers={
        "X-Device-Id": dev_id, "X-Timestamp": str(ts), "X-Nonce": nonce,
        "X-Signature": sign("GET", "/v1/ping", "", ts, nonce, b"")})
    with urllib.request.urlopen(req) as r:
        got = r.headers.get("X-Server-Proof", "")
    want = hmac.new(bytes.fromhex(secret), f"proof\n{nonce}".encode(), hashlib.sha256).hexdigest()
    check("server proof header", got == want, True)

    # LAN token from loopback must NOT work (loopback is not a trusted LAN peer)
    check("LAN token from loopback", call("GET", "/v1/ping", headers={"Authorization": "Bearer " + cfg["lan_token"]}, signed=False)[0], 401)
    # a public proxy adding X-Forwarded-For must never get the LAN tier
    check("LAN token via proxy header", call("GET", "/v1/ping", headers={"Authorization": "Bearer " + cfg["lan_token"], "X-Forwarded-For": "192.168.5.9"}, signed=False)[0], 401)

    config.revoke_device(dev_id)
    check("revoked device", call("GET", "/v1/ping")[0], 401)
finally:
    config.revoke_device(dev_id)

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
