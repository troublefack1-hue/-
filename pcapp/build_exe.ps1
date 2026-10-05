# Builds "PC Remote.exe" (single file) with PyInstaller.
# Run from pc-remote\pcapp:   powershell -ExecutionPolicy Bypass -File build_exe.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
Set-Location $here

if (-not (Test-Path "$here\venv")) { python -m venv "$here\venv" }
& "$here\venv\Scripts\pip.exe" install -q -r "$root\agent\requirements.txt" -r "$root\relay\requirements.txt" cryptography pyinstaller

& "$here\venv\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --windowed --name "PC Remote" `
    --add-data "$root\relay;relay" --add-data "$root\agent;agent" --add-data "$root\web;web" `
    --hidden-import mss.windows --hidden-import PIL._tkinter_finder `
    "$here\main.py"

Write-Host "Done: $here\dist\PC Remote.exe" -ForegroundColor Green
