# pc-remote, local mode (no VPS).
# Runs relay + agent on this PC and exposes the relay through a free
# Cloudflare quick tunnel. The tunnel URL changes on every start, so it is
# published to an ntfy channel; the launcher page on the phone reads it.
#
# Usage (from pc-remote\local):   powershell -File run.ps1
# install.ps1 registers this as a scheduled task at logon.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
$py = "$root\agent\venv\Scripts\python.exe"
$cloudflared = "$here\cloudflared.exe"
$log = "$here\run.log"

function Log($m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $log -Append }

# --- 1. relay (listens on 127.0.0.1:8787) ---------------------------------
$env:PC_REMOTE_CONFIG = "$here\relay.json"
@{ secret = $cfg.secret; host = "127.0.0.1"; port = 8787; ntfy_wake_url = $cfg.ntfy_wake_url } |
    ConvertTo-Json | Set-Content "$here\relay.json" -Encoding UTF8
$relay = Start-Process $py -ArgumentList "`"$root\relay\relay.py`"" -WorkingDirectory "$root\relay" -PassThru -WindowStyle Hidden
Log "relay started (pid $($relay.Id))"

# --- 2. agent (connects to the local relay) --------------------------------
@{ relay_url = "http://127.0.0.1:8787"; secret = $cfg.secret; max_width = 1280; quality = 55; fps = 12; monitor = 1 } |
    ConvertTo-Json | Set-Content "$root\agent\config.json" -Encoding UTF8
$agent = Start-Process $py -ArgumentList "`"$root\agent\agent.py`"" -WorkingDirectory "$root\agent" -PassThru -WindowStyle Hidden
Log "agent started (pid $($agent.Id))"

# --- 3. tunnel: restart forever, publish each new URL ----------------------
while ($true) {
    $tlog = "$here\tunnel.log"
    Remove-Item $tlog -ErrorAction SilentlyContinue
    $tun = Start-Process $cloudflared -ArgumentList "tunnel --url http://127.0.0.1:8787 --no-autoupdate" `
        -RedirectStandardError $tlog -PassThru -WindowStyle Hidden
    Log "cloudflared started (pid $($tun.Id))"
    $url = $null
    for ($i = 0; $i -lt 60 -and -not $url; $i++) {
        Start-Sleep 1
        if (Test-Path $tlog) {
            $m = Select-String -Path $tlog -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1
            if ($m) { $url = $m.Matches[0].Value }
        }
    }
    if ($url) {
        Log "tunnel url: $url"
        try {
            Invoke-RestMethod -Method Post -Uri $cfg.ntfy_url_channel -Body $url `
                -Headers @{ Title = "pc-remote url"; Tags = "link" } | Out-Null
            Log "url published to ntfy"
        } catch { Log "ntfy publish failed: $_" }
    } else {
        Log "no tunnel url after 60 s, restarting cloudflared"
        Stop-Process -Id $tun.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep 5
        continue
    }
    # re-publish every 10 min so the launcher always finds a fresh URL,
    # and restart the tunnel if cloudflared dies
    while (-not $tun.HasExited) {
        Start-Sleep 600
        if ($tun.HasExited) { break }
        try { Invoke-RestMethod -Method Post -Uri $cfg.ntfy_url_channel -Body $url -Headers @{ Title = "pc-remote url"; Tags = "link" } | Out-Null } catch {}
    }
    Log "cloudflared exited, restarting in 5 s"
    Start-Sleep 5
}
