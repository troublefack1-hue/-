# Installs the pc-remote agent on Windows and registers it to start at logon.
# Run in PowerShell *as Administrator* from this folder:
#   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Test-Path "$here\config.json")) {
    Copy-Item "$here\config.example.json" "$here\config.json"
    Write-Host "Created config.json - fill in relay_url and secret, then run install.ps1 again." -ForegroundColor Yellow
    exit 1
}

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw "Python 3 not found. Install from https://www.python.org/downloads/ (tick 'Add to PATH')." }

if (-not (Test-Path "$here\venv")) { python -m venv "$here\venv" }
& "$here\venv\Scripts\pip.exe" install -q -r "$here\requirements.txt"

# Scheduled task: runs in the interactive session (needed for screen capture),
# highest privileges, restarts if it dies, no time limit.
$taskName = "pc-remote agent"
$pyw = "$here\venv\Scripts\pythonw.exe"
$action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$here\agent.py`"" -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest

Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal | Out-Null
Start-ScheduledTask -TaskName $taskName

Write-Host "Agent installed and started. Log: $here\agent.log" -ForegroundColor Green
