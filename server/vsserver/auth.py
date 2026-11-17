"""
Request authentication.

Two tiers:

1. Signed requests (required for everything that is not a trusted direct LAN peer).
   The device secret is never sent. Each request carries
       X-Device-Id, X-Timestamp (unix s), X-Nonce (random), X-Signature
   where Signature = HMAC-SHA256(secret, "v1\\nMETHOD\\nLOGICAL_PATH\\nQUERY\\nTS\\nNONCE\\nSHA256(body)")
   - the signed path is the *logical* route ("/v1/transcribe"), so a public server that mounts
     or rewrites the URL prefix does not break signatures
   - the timestamp must be within +-clock_skew_seconds and the nonce may be used only once, so a
     captured request cannot be replayed
   - the body hash binds the signature to the audio, the query to the language/options

2. LAN token (only for direct peers inside `trusted_lan`, and only if the request carries no
   proxy headers): "Authorization: Bearer <lan_token>". Lower security by design, meant for
   your home network over plain http.
"""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import time
import threading

from fastapi import HTTPException, Request

from . import config

PROXY_HEADERS = ("x-forwarded-for", "forwarded", "x-real-ip", "x-forwarded-host", "x-forwarded-proto", "via")


class _Limiter:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.fails: dict[str, list[float]] = {}
        self.blocked: dict[str, float] = {}

    def check(self, key: str, cfg: dict) -> None:
        now = time.time()
        with self.lock:
            until = self.blocked.get(key, 0)
            if until > now:
                raise HTTPException(429, "too many failed attempts", headers={"Retry-After": str(int(until - now) + 1)})
            self.blocked.pop(key, None)

    def fail(self, key: str, cfg: dict) -> None:
        now = time.time()
        with self.lock:
            recent = [t for t in self.fails.get(key, []) if now - t < cfg["fail_window_s"]]
            recent.append(now)
            self.fails[key] = recent
            if len(recent) >= cfg["fail_limit"]:
                self.blocked[key] = now + cfg["block_s"]
                self.fails.pop(key, None)

    def ok(self, key: str) -> None:
        with self.lock:
            self.fails.pop(key, None)


limiter = _Limiter()


class _NonceCache:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.seen: dict[str, float] = {}

    def use(self, key: str, ttl: float) -> bool:
        """True if the nonce was fresh (and is now remembered)."""
        now = time.time()
        with self.lock:
            if len(self.seen) > 5000:
                self.seen = {k: v for k, v in self.seen.items() if v > now}
            if self.seen.get(key, 0) > now:
                return False
            self.seen[key] = now + ttl
            return True


nonces = _NonceCache()


def _nets(items: list[str]):
    out = []
    for s in items:
        try:
            out.append(ipaddress.ip_network(s, strict=False))
        except ValueError:
            pass
    return out


def _in(ip: str, nets) -> bool:
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return any(a in n for n in nets)


def client_key(request: Request, cfg: dict) -> str:
    """Identity used for rate limiting. X-Forwarded-For is honoured only from trusted proxies."""
    peer = request.client.host if request.client else "?"
    if _in(peer, _nets(cfg["trusted_proxies"])):
        xff = request.headers.get("x-forwarded-for", "")
        if xff:
            return xff.split(",")[0].strip()
    return peer


def canonical_query(request: Request) -> str:
    return "&".join(f"{k}={v}" for k, v in sorted(request.query_params.items()))


def is_lan_peer(request: Request, cfg: dict) -> bool:
    peer = request.client.host if request.client else ""
    if any(h in request.headers for h in PROXY_HEADERS):
        return False
    return _in(peer, _nets(cfg["trusted_lan"]))


class Auth:
    """Result of a successful authentication."""

    def __init__(self, tier: str, name: str, verify_body, proof: str = ""):
        self.tier = tier
        self.name = name
        self.proof = proof  # server-to-client proof (signed requests only), see pre_authenticate
        self._verify_body = verify_body  # callable(body_sha256_hex) -> None (raises on mismatch)

    def finish(self, body_sha256_hex: str) -> None:
        self._verify_body(body_sha256_hex)


def pre_authenticate(request: Request, logical_path: str) -> Auth:
    """
    Everything that can be checked WITHOUT reading the body. Unauthenticated callers never get
    their body read. For signed requests the caller must call `.finish(body_hash)` afterwards.
    """
    cfg = config.load_config()
    key = client_key(request, cfg)
    limiter.check(key, cfg)

    def deny() -> HTTPException:
        limiter.fail(key, cfg)
        return HTTPException(401, "unauthorized")

    h = request.headers
    if "x-signature" in h:
        dev_id = h.get("x-device-id", "")
        dev = config.load_devices().get(dev_id)
        ts_raw, nonce, sig = h.get("x-timestamp", ""), h.get("x-nonce", ""), h.get("x-signature", "")
        if not dev or dev.get("revoked") or not nonce or len(nonce) > 64 or len(sig) != 64:
            raise deny()
        try:
            ts = int(ts_raw)
        except ValueError:
            raise deny()
        if abs(time.time() - ts) > cfg["clock_skew_seconds"]:
            raise deny()
        method = request.method.upper()
        query = canonical_query(request)
        secret = bytes.fromhex(dev["secret"])

        def verify(body_hash: str) -> None:
            msg = f"v1\n{method}\n{logical_path}\n{query}\n{ts}\n{nonce}\n{body_hash}".encode()
            expect = hmac.new(secret, msg, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expect, sig.lower()):
                raise deny()
            if not nonces.use(f"{dev_id}:{nonce}", cfg["clock_skew_seconds"] * 2 + 30):
                raise deny()  # replay
            limiter.ok(key)

        # Mutual authentication: the server proves it knows the secret by signing the client's nonce.
        # The app checks this before sending audio to an address on the local network.
        proof = hmac.new(secret, f"proof\n{nonce}".encode(), hashlib.sha256).hexdigest()
        return Auth("signed", dev["name"], verify, proof)

    if is_lan_peer(request, cfg):
        auth = h.get("authorization", "")
        if auth.lower().startswith("bearer ") and hmac.compare_digest(auth[7:].strip(), cfg["lan_token"]):
            limiter.ok(key)
            return Auth("lan", "lan", lambda _h: None)

    raise deny()
