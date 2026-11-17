"""VoiceScribe transcription server (faster-whisper on the GPU)."""
from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import os
import sys
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from . import auth, config

log = logging.getLogger("vsserver")


def _add_cuda_dlls() -> None:
    """On Windows the pip-installed CUDA libraries live in site-packages/nvidia/*/bin."""
    if os.name != "nt":
        return
    import sysconfig

    root = Path(sysconfig.get_paths()["purelib"]) / "nvidia"
    for d in root.glob("*/bin"):
        os.add_dll_directory(str(d))
        os.environ["PATH"] = str(d) + os.pathsep + os.environ.get("PATH", "")


_add_cuda_dlls()

state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    from faster_whisper import WhisperModel

    cfg = config.load_config()
    t = time.time()
    log.info("loading %s on %s (%s) ...", cfg["model"], cfg["device"], cfg["compute_type"])
    state["model"] = WhisperModel(cfg["model"], device=cfg["device"], compute_type=cfg["compute_type"])
    state["lock"] = asyncio.Semaphore(1)  # one transcription at a time on the GPU
    log.info("model ready in %.1fs", time.time() - t)
    yield


# no interactive docs / schema: nothing to browse for strangers
app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


async def read_body(request: Request, limit: int) -> tuple[bytes, str]:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise HTTPException(413, "payload too large")
    h = hashlib.sha256()
    chunks = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            raise HTTPException(413, "payload too large")
        h.update(chunk)
        chunks.append(chunk)
    return b"".join(chunks), h.hexdigest()


@app.get("/v1/ping")
async def ping(request: Request):
    a = auth.pre_authenticate(request, "/v1/ping")
    a.finish(hashlib.sha256(b"").hexdigest())
    cfg = config.load_config()
    headers = {"X-Server-Proof": a.proof} if a.proof else {}
    return JSONResponse({"ok": True, "tier": a.tier, "model": cfg["model"]}, headers=headers)


@app.post("/v1/transcribe")
async def transcribe(request: Request):
    cfg = config.load_config()
    a = auth.pre_authenticate(request, "/v1/transcribe")
    body, digest = await read_body(request, cfg["max_upload_mb"] * 1024 * 1024)
    a.finish(digest)
    if not body:
        raise HTTPException(400, "empty body")

    lang = request.query_params.get("language", "de")
    if lang in ("", "auto"):
        lang = None
    elif not (2 <= len(lang) <= 3 and lang.isalpha()):
        raise HTTPException(400, "bad language")

    try:
        await asyncio.wait_for(state["lock"].acquire(), timeout=cfg["queue_timeout_s"])
    except asyncio.TimeoutError:
        raise HTTPException(503, "busy, try again")
    try:
        t0 = time.time()
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(None, _run, body, lang, cfg["max_audio_minutes"])
    finally:
        state["lock"].release()

    result["elapsed"] = round(time.time() - t0, 2)
    # log metadata only - never the transcript
    log.info("%s [%s] %.1fs audio -> %d chars in %.1fs", a.name, a.tier, result["duration"], len(result["text"]), result["elapsed"])
    return JSONResponse(result)


def _run(body: bytes, lang: str | None, max_minutes: int) -> dict:
    segments, info = state["model"].transcribe(
        io.BytesIO(body),
        language=lang,
        beam_size=5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
        condition_on_previous_text=False,  # avoids runaway repetition loops
        temperature=[0.0, 0.2, 0.4],
    )
    if info.duration > max_minutes * 60:
        raise HTTPException(413, "audio too long")
    text = " ".join(s.text.strip() for s in segments).strip()
    return {"text": text, "language": info.language, "duration": round(info.duration, 1),
            "model": config.load_config()["model"]}


@app.exception_handler(Exception)
async def unhandled(request: Request, exc: Exception):
    log.exception("unhandled error")
    return JSONResponse({"detail": "server error"}, status_code=500)


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = config.load_config()
    log.info("listening on %s:%s  (LAN tier for %s)", cfg["host"], cfg["port"], ", ".join(cfg["trusted_lan"]))
    # server header / access log off: less noise, less fingerprinting
    uvicorn.run(app, host=cfg["host"], port=cfg["port"], server_header=False, access_log=False, proxy_headers=False)


if __name__ == "__main__":
    main()
