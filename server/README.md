# VoiceScribe server

GPU transcription (faster-whisper `large-v3`) for the VoiceScribe app's **Hoch** mode. Runs on Windows and Linux.

## Requirements

- A PC or server that is on when you want to transcribe. Windows is what this was built and tested on; Linux is
  supported with the same code (`run.sh` instead of `run.ps1`) but has not been run on real hardware yet.
- An NVIDIA GPU with a recent driver is strongly recommended (`large-v3` needs roughly 4 GB of VRAM in float16;
  the reference setup is an RTX 2080 Ti: 90 s of audio in about 21 s). No separate CUDA toolkit is needed,
  the CUDA 12 libraries come from pip. Without a GPU it still works on the CPU, only much slower (see "CPU only").
- Python 3.10 or newer (tested with 3.14) and about 3 GB of free disk space for the model.
  On Debian/Ubuntu you may need `sudo apt install python3-venv`.

## Setup

Run everything from the `server` folder. Everything the server stores (config, device secrets, the model cache)
stays inside this folder unless you set `HF_HOME`; if your system drive is small, put the repo on another one.
Commands are given for **Windows (PowerShell)** and **Linux (bash)**.

**1. Create the environment and install the dependencies**

Windows:

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Linux:

```bash
cd server
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
```

This installs faster-whisper, FastAPI/uvicorn and the NVIDIA CUDA 12 libraries (a few hundred MB to over 1 GB).
For the examples below, `PY` means `.\.venv\Scripts\python.exe` on Windows and `.venv/bin/python` on Linux.

**2. Create the configuration and find your network**

```
PY manage.py init
```

This writes `data/config.json`, generates a random LAN token and prints the network it detected
(`trusted_lan`). Check that it is the network your phone uses at home; if not, set it in the next step.

**3. Tell the server its addresses**

```
PY manage.py set --lan-url http://<pc-lan-ip>:8765 --lan-net <phone-network, e.g. 192.168.5.0/24>
```

- `--lan-url` is how the phone reaches this machine at home. Find its address with `ipconfig` (Windows) or
  `ip -4 addr` / `hostname -I` (Linux).
- `--lan-net` is the home network (CIDR) whose devices count as "trusted LAN". It may be given several times.
- Optional: `--public-url https://<your-public-server>/<path>` is the internet address the phone uses when
  it is not at home (see "Reaching the server from outside"). `--port` changes the port (default 8765).

**4. Start the server**

Windows: `powershell -File .\run.ps1`  
Linux: `./run.sh`

The first start downloads `large-v3` from Hugging Face (about 3 GB) and then loads it; later starts take about
30 seconds. When you see `Uvicorn running on http://0.0.0.0:8765` it is ready. Ctrl+C stops it.
By default the model is cached in `server/models`; set `HF_HOME` before starting to put it elsewhere
(`$env:HF_HOME = "D:\models"` on Windows, `export HF_HOME=/data/models` on Linux).

**5. Open the port in the firewall**, for your home network only

Windows (administrator PowerShell, Private network profile):

```powershell
New-NetFirewallRule -DisplayName "VoiceScribe" -Direction Inbound -Protocol TCP -LocalPort 8765 -Profile Private -Action Allow
```

Linux with ufw (use your own network):

```bash
sudo ufw allow from 192.168.5.0/24 to any port 8765 proto tcp
```

If you only reach the server through a tunnel on the same machine, you don't need to open the port at all.

**6. Pair your phone**

```
PY manage.py add-device "my phone"
```

This prints a pairing code (`vs1:...`) **once**. In the app choose **Hoch**, paste the code into
"Kopplungscode" and tap "Verbinden", then "Verbindung testen". The code contains the device secret:
send it to yourself, paste it, delete the message. One code per device; list devices with `manage.py list` and
cut one off with `manage.py revoke d_xxxx` (takes effect immediately). Create the code **after** step 3, since
the addresses are baked into it. If you change them later, add the device again.

**7. Check the server**

```
PY tests/client_test.py "<some voice message>.ogg"
```

(use `tests\client_test.py` on Windows). It should print `14/14 passed` and a transcript.
It runs the auth checks (replay, tampering, stale timestamp, revoked device, LAN token from loopback/proxy)
against the running server plus one real transcription.

### Run it as a service (Linux, optional)

`/etc/systemd/system/voicescribe.service` (adjust the user and paths):

```ini
[Unit]
Description=VoiceScribe transcription server
After=network-online.target

[Service]
User=voicescribe
WorkingDirectory=/opt/VoiceScribe/server
ExecStart=/opt/VoiceScribe/server/run.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then `sudo systemctl enable --now voicescribe`. Create the `data/` folder as that same user (run steps 2, 3 and 6 as
that user) so the device secrets are not readable by anyone else.

### Reaching the server from outside

At home the app finds the server directly through `--lan-url`. Away from home it uses `--public-url`: a server
on the internet that forwards requests to this machine. Point that server's `ai-endpoint` (or reverse-proxy target)
at `http://<pc>:8765` through your tunnel. The server answers only `/v1/ping` and `/v1/transcribe`;
there are no docs pages or other routes. Use HTTPS on the public side and an encrypted tunnel for the hop to this
machine (see "Things you must still do yourself"); the app refuses `http://` for anything except private IP addresses.

### CPU only

Skip the three `nvidia-*` lines in `requirements.txt`, then edit `data/config.json`:
`"device": "cpu"`, `"compute_type": "int8"`, and for reasonable speed `"model": "small"` or `"medium"`.

### Other settings

All in `data/config.json` (restart the server after editing): `model`, `port`, `max_upload_mb` (25),
`max_audio_minutes` (15), `clock_skew_seconds` (120), and the lockout values `fail_limit` / `fail_window_s` / `block_s`.
If your public server runs on this machine or reaches it from a known address, list that address in `trusted_proxies`.

### Troubleshooting

- **Phone says it is not reachable at home:** the phone and server are on different networks (for example a guest
  Wi-Fi or another subnet), the firewall rule is missing, or `--lan-url` has the wrong address. Test from the server
  with `curl http://<lan-ip>:8765/v1/ping`: a `401` means the server is reachable (the ping requires a signature).
- **"Anmeldung abgelehnt":** the device was revoked, or the phone's clock is off by more than 2 minutes
  (enable automatic time).
- **`Could not load library libcudnn...` / `cublas64_12.dll not found`:** the NVIDIA pip packages are missing or
  were installed into another environment; re-run step 1 with the `.venv` Python. On Linux also start the server with
  `run.sh` (it exports `LD_LIBRARY_PATH` for them) and make sure the NVIDIA driver works (`nvidia-smi`).
- **`av.open() got an unexpected keyword argument 'metadata_errors'`:** `av` is too new; `pip install "av<17"`.
- **Out of GPU memory:** set `"compute_type": "int8_float16"` or use `"model": "medium"`.

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

- **Hop between your public server and this machine:** the signature protects the secret, but the audio itself is only
  as private as that hop. Use an encrypted tunnel (SSH `-R`, WireGuard, Cloudflare Tunnel) and HTTPS on the public side.
  The app refuses `http://` for anything that is not a private IP address.
- **Firewall:** allow inbound TCP 8765 only from your LAN / tunnel (Windows: *Private* profile only).
- `data/devices.json` holds the device secrets (the server needs them to verify signatures). The folder is restricted
  to your user (Windows ACL / mode 700 on Linux); don't copy it around or back it up to the cloud.
- Keep the phone clock correct (automatic time), otherwise requests are rejected as stale.
