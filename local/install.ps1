# Installs pc-remote in local mode (relay + agent + Cloudflare tunnel on this PC).
# Run in PowerShell *as Administrator* from pc-remote\local:
#   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here

if (-not (Test-Path "$here\config.json")) {
    $secret = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ })
    $chan = "pcremote-url-" + (-join ((48..57 + 97..122) | Get-Random -Count 16 | ForEach-Object { [char]$_ }))
    (Get-Content "$here\config.example.json" -Raw) `
        -replace "CHANGE-ME-to-a-long-random-string-32-chars", $secret `
        -replace "CHANGE-ME-url-channel-name", $chan |
        Set-Content "$here\config.json" -Encoding UTF8
    Write-Host "Created config.json" -ForegroundColor Yellow
    Write-Host "  secret:       $secret"
    Write-Host "  url channel:  https://ntfy.sh/$chan"
    Write-Host "Edit ntfy_wake_url in config.json if you want the Wake button, then run install.ps1 again."
    exit 1
}

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw "Python 3 not found. Install from https://www.python.org/downloads/ (tick 'Add to PATH')." }
if (-not (Test-Path "$root\agent\venv")) { python -m venv "$root\agent\venv" }
& "$root\agent\venv\Scripts\pip.exe" install -q -r "$root\agent\requirements.txt" -r "$root\relay\requirements.txt"

if (-not (Test-Path "$here\cloudflared.exe")) {
    Write-Host "Downloading cloudflared..."
    Invoke-WebRequest -Uri "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" `
        -OutFile "$here\cloudflared.exe"
}

$taskName = "pc-remote local"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$here\run.ps1`"" -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest

Unregister-ScheduledTask -TaskName "pc-remote agent" -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Start-ScheduledTask -TaskName $taskName

$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
Write-Host "Installed and started. Log: $here\run.log" -ForegroundColor Green
Write-Host "Launcher page for the phone: open launcher\index.html (or its GitHub Pages copy) and enter:"
Write-Host "  url channel: $($cfg.ntfy_url_channel)"
Write-Host "  secret:      $($cfg.secret)"
