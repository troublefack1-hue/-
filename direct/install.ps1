# Installs pc-remote in direct mode (white static IP, no third-party services).
# Run in PowerShell *as Administrator* from pc-remote\direct:
#   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here

if (-not (Test-Path "$here\config.json")) {
    $secret = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ })
    try { $ip = (Invoke-RestMethod "https://api.ipify.org" -TimeoutSec 10).Trim() } catch { $ip = "0.0.0.0" }
    (Get-Content "$here\config.example.json" -Raw) `
        -replace "CHANGE-ME-to-a-long-random-string-32-chars", $secret -replace '"0.0.0.0"', "`"$ip`"" |
        Set-Content "$here\config.json" -Encoding UTF8
    Write-Host "Created config.json" -ForegroundColor Yellow
    Write-Host "  public_ip: $ip   (check it; this is what the phone will connect to)"
    Write-Host "  secret:    $secret"
    Write-Host "Optionally set ntfy_wake_url, then run install.ps1 again."
    exit 1
}
$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
if ($cfg.public_ip -eq "0.0.0.0") { throw "Set public_ip in config.json first." }

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw "Python 3 not found. Install from https://www.python.org/downloads/ (tick 'Add to PATH')." }
if (-not (Test-Path "$root\agent\venv")) { python -m venv "$root\agent\venv" }
$venvpy = "$root\agent\venv\Scripts\python.exe"
& "$root\agent\venv\Scripts\pip.exe" install -q -r "$root\agent\requirements.txt" -r "$root\relay\requirements.txt" cryptography

# our own CA + server certificate for the public IP
& $venvpy "$here\make_cert.py" $cfg.public_ip

# Windows Firewall
$name = "pc-remote $($cfg.port)"
if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol TCP -LocalPort $cfg.port -Action Allow | Out-Null
}

$taskName = "pc-remote direct"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$here\run.ps1`"" -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
foreach ($old in "pc-remote agent", "pc-remote local", $taskName) {
    Unregister-ScheduledTask -TaskName $old -Confirm:$false -ErrorAction SilentlyContinue
}
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Start-ScheduledTask -TaskName $taskName

$lan = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } | Select-Object -First 1).IPAddress
Write-Host ""
Write-Host "Installed and started. Logs: $here\run.log, $here\relay.log" -ForegroundColor Green
Write-Host "1. Router: forward TCP $($cfg.port) to this PC ($lan)."
Write-Host "2. Phone, once: open https://$($cfg.public_ip):$($cfg.port)/ca.crt, accept the warning,"
Write-Host "   install the downloaded file as a CA certificate (Settings > Security > Install certificate)."
Write-Host "3. Phone: open https://$($cfg.public_ip):$($cfg.port) and enter the secret:"
Write-Host "   $($cfg.secret)"
