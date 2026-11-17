# Starts the VoiceScribe server using the virtual environment in .\.venv (see README, "Setup").
# Optional overrides (set before running):
#   $env:VOICESCRIBE_PYTHON  path to another python.exe
#   $env:HF_HOME             where the Whisper model (~3 GB) is cached; default: .\models
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$python = if ($env:VOICESCRIBE_PYTHON) { $env:VOICESCRIBE_PYTHON } else { Join-Path $PSScriptRoot ".venv\Scripts\python.exe" }
if (-not (Test-Path $python)) { throw "Python not found at $python - follow the Setup section in README.md first." }

if (-not $env:HF_HOME) { $env:HF_HOME = Join-Path $PSScriptRoot "models" }
New-Item -ItemType Directory -Force $env:HF_HOME | Out-Null

& $python -m vsserver
