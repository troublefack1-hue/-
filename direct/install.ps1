# Installs pc-remote in direct mode (white IP): relay + agent + Caddy on this PC.
# Run in PowerShell *as Administrator* from pc-remote\direct:
#   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here

if (-not (Test-Path "$here\config.json")) {
    $secret = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ })
    (Get-Content "$here\config.example.json" -Raw) -replace "CHANGE-ME-to-a-long-random-string-32-chars", $secret |
        Set-Content "$here\config.json" -Encoding UTF8
    Write-Host "Created config.json with secret: $secret" -ForegroundColor Yellow
    Write-Host "Fill in 'domain' (and 'duckdns_token' if the IP is dynamic), then run install.ps1 again."
    exit 1
}
$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
if ($cfg.domain -eq "myhome.duckdns.org") { throw "Set your real domain in config.json first." }

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw "Python 3 not found. Install from https://www.python.org/downloads/ (tick 'Add to PATH')." }
if (-not (Test-Path "$root\agent\venv")) { python -m venv "$root\agent\venv" }
& "$root\agent\venv\Scripts\pip.exe" install -q -r "$root\agent\requirements.txt" -r "$root\relay\requirements.txt"

if (-not (Test-Path "$here\caddy.exe")) {
    Write-Host "Downloading Caddy..."
    Invoke-WebRequest -Uri "https://caddyserver.com/api/download?os=windows&arch=amd64" -OutFile "$here\caddy.exe"
}

# Windows Firewall: let Caddy accept 80/443 from the internet
foreach ($port in 80, 443) {
    $name = "pc-remote $port"
    if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol TCP -LocalPort $port -Action Allow | Out-Null
    }
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

$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } | Select-Object -First 1).IPAddress
Write-Host "Installed and started. Log: $here\run.log, $here\caddy.log" -ForegroundColor Green
Write-Host "On the router forward TCP 80 and 443 to this PC ($ip)."
Write-Host "Phone: open https://$($cfg.domain) and enter the secret:"
Write-Host "  $($cfg.secret)"
