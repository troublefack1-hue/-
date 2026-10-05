# Builds "PC Remote.exe" (single file) with PyInstaller.
# Run from pc-remote\pcapp:   powershell -ExecutionPolicy Bypass -File build_exe.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
Set-Location $here

if (-not (Test-Path "$here\venv")) { python -m venv "$here\venv" }
& "$here\venv\Scripts\pip.exe" install -q -r "$root\agent\requirements.txt" -r "$root\relay\requirements.txt" cryptography pystray qrcode dxcam pyinstaller

& "$here\venv\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --windowed --name "PC Remote" `
    --add-data "$root\relay;relay" --add-data "$root\agent;agent" --add-data "$root\web;web" --add-data "$root\pcapp\phone_cli;pcapp\phone_cli" `
    --paths "$root\relay" --paths "$root\agent" --paths "$here" `
    --hidden-import agent --hidden-import relay --hidden-import certs --hidden-import netproxy --hidden-import audio_out --hidden-import capture --hidden-import extras --hidden-import notify_watch --hidden-import opus --hidden-import video --hidden-import apksign --hidden-import deps --hidden-import updater `
    --hidden-import send2trash --hidden-import psutil --hidden-import pyaudiowpatch --hidden-import qrcode --collect-all aiohttp `
    --hidden-import mss.windows --hidden-import PIL._tkinter_finder --hidden-import pystray._win32 --collect-all winpty --collect-all comtypes --collect-all pycaw --collect-all dxcam `
    "$here\main.py"

Write-Host "Done: $here\dist\PC Remote.exe" -ForegroundColor Green
