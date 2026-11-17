# Starts the VoiceScribe server with the venv from D:\VoiceScribeRef (Hugging Face cache stays on D:).
$env:HF_HOME = "D:\VoiceScribeRef\hf"
$env:TEMP = "D:\VoiceScribeRef\tmp"
$env:TMP  = "D:\VoiceScribeRef\tmp"
Set-Location $PSScriptRoot
& "D:\VoiceScribeRef\venv\Scripts\python.exe" -m vsserver
