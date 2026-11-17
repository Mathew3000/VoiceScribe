# VoiceScribe server

GPU transcription (faster-whisper `large-v3`) for the VoiceScribe app's **Hoch** mode.

## Start / stop

    powershell -File D:\VoiceScribeServer\run.ps1

Listens on `0.0.0.0:8765` (change with `manage.py set --port`). Ctrl+C stops it.
Model cache and temp files live on `D:\VoiceScribeRef` (nothing on C:).

## One-time setup

    cd D:\VoiceScribeServer
    D:\VoiceScribeRef\venv\Scripts\python.exe manage.py init
    D:\VoiceScribeRef\venv\Scripts\python.exe manage.py set --lan-url http://<pc-lan-ip>:8765 --public-url https://<your-public-server>/<path> --lan-net <your LAN, e.g. 192.168.5.0/24>
    D:\VoiceScribeRef\venv\Scripts\python.exe manage.py add-device "my phone"

`add-device` prints a pairing code (`vs1:...`) **once**. Paste it into the app (Hoch -> Kopplungscode).
It contains the device secret: send it to yourself, paste it, delete the message.
One code per device; revoke a lost phone with `manage.py revoke d_xxxx` (takes effect immediately).
`--lan-net` can be given several times. It must match the network the phone is in at home.

Point your public server's `ai-endpoint` at `http://<pc>:8765` (through your tunnel). The server answers
only `/v1/ping` and `/v1/transcribe`; there are no docs pages or other routes.

## How access is protected

Everything except a trusted direct LAN peer must send a **signed request**:

- Each phone has its own 256-bit secret. The secret is never sent; every request carries
  `HMAC-SHA256(secret, method | path | query | timestamp | nonce | SHA256(audio))`.
- Timestamp must be within 2 minutes and a nonce works once, so captured requests cannot be replayed.
- The signature covers the audio and the options, so nothing can be swapped in transit.
- The path signed is the logical route (`/v1/transcribe`), so your public server may mount it under any prefix.
- Failed attempts are counted per client; 10 failures in 10 minutes blocks that client for 10 minutes.
- Unauthenticated callers never get their upload read (uploads are capped at 25 MB, audio at 15 minutes).
- One transcription at a time on the GPU, others queue (max 90 s).
- The server logs device name, audio length and timing, **never** the transcript.
- The server proves it knows the secret (`X-Server-Proof`), and the app checks that before sending audio
  to a LAN address, so a stranger's device on another Wi-Fi with the same private IP gets nothing.

**LAN tier (lower security, for your home network):** a direct peer inside `trusted_lan` may instead send
`Authorization: Bearer <lan token>` (see `manage.py show-lan-token`) over plain http, handy for curl/scripts.
It is refused whenever the request carries a proxy header (`X-Forwarded-For`, ...), and loopback is **not** trusted,
because a tunnel from your public server arrives as 127.0.0.1. The app itself always signs.

## Things you must still do yourself

- **Hop between your public server and this PC:** the signature protects the secret, but the audio itself is only
  as private as that hop. Use an encrypted tunnel (SSH `-R`, WireGuard, Cloudflare Tunnel) and HTTPS on the public side.
  The app refuses `http://` for anything that is not a private IP address.
- **Windows Firewall:** allow inbound TCP 8765 for the *Private* profile only (or only from your LAN / tunnel).
- `data\devices.json` holds the device secrets (the server needs them to verify signatures). The folder is restricted
  to your Windows user; don't copy it around or back it up to the cloud.
- Keep the phone clock correct (automatic time), otherwise requests are rejected as stale.

## Test

    D:\VoiceScribeRef\venv\Scripts\python.exe tests\client_test.py "<some voice message>.ogg"

Runs the auth checks (replay, tampering, stale timestamp, revoked device, LAN token from loopback/proxy) against
the running server and one real transcription.
