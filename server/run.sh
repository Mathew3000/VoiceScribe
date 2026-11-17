#!/usr/bin/env bash
# Starts the VoiceScribe server using the virtual environment in ./.venv (see README, "Setup").
# Optional overrides:  VOICESCRIBE_PYTHON=/path/to/python   HF_HOME=/where/to/cache/the/model
set -euo pipefail
cd "$(dirname "$0")"

PY="${VOICESCRIBE_PYTHON:-$PWD/.venv/bin/python}"
[ -x "$PY" ] || { echo "Python not found at $PY - follow the Setup section in README.md first." >&2; exit 1; }

export HF_HOME="${HF_HOME:-$PWD/models}"
mkdir -p "$HF_HOME"

# pip-installed CUDA 12 libraries (nvidia-cublas-cu12, nvidia-cudnn-cu12, ...)
SITE="$("$PY" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
for d in "$SITE"/nvidia/*/lib; do
  [ -d "$d" ] && export LD_LIBRARY_PATH="$d${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
done

exec "$PY" -m vsserver
