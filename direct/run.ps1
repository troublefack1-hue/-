# pc-remote, direct mode (white IP, no intermediary).
# Runs relay + agent on this PC and Caddy in front of them: Caddy listens on
# 80/443, gets a Let's Encrypt certificate for the domain and proxies to the
# relay. The router must forward TCP 80 and 443 to this PC.
# If duckdns_token is set, the public IP is pushed to DuckDNS every 5 min.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
$py = "$root\agent\venv\Scripts\python.exe"
$log = "$here\run.log"
function Log($m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $log -Append }

# --- relay on 127.0.0.1:8787 ------------------------------------------------
$env:PC_REMOTE_CONFIG = "$here\relay.json"
@{ secret = $cfg.secret; host = "127.0.0.1"; port = 8787; ntfy_wake_url = $cfg.ntfy_wake_url } |
    ConvertTo-Json | Set-Content "$here\relay.json" -Encoding UTF8
$relay = Start-Process $py -ArgumentList "`"$root\relay\relay.py`"" -WorkingDirectory "$root\relay" -PassThru -WindowStyle Hidden
Log "relay started (pid $($relay.Id))"

# --- agent ------------------------------------------------------------------
@{ relay_url = "http://127.0.0.1:8787"; secret = $cfg.secret; max_width = 1280; quality = 55; fps = 12; monitor = 1 } |
    ConvertTo-Json | Set-Content "$root\agent\config.json" -Encoding UTF8
$agent = Start-Process $py -ArgumentList "`"$root\agent\agent.py`"" -WorkingDirectory "$root\agent" -PassThru -WindowStyle Hidden
Log "agent started (pid $($agent.Id))"

# --- caddy: HTTPS with automatic certificate --------------------------------
@"
$($cfg.domain) {
    reverse_proxy 127.0.0.1:8787
}
"@ | Set-Content "$here\Caddyfile" -Encoding ASCII
$caddy = Start-Process "$here\caddy.exe" -ArgumentList "run --config `"$here\Caddyfile`"" -WorkingDirectory $here `
    -RedirectStandardError "$here\caddy.log" -PassThru -WindowStyle Hidden
Log "caddy started (pid $($caddy.Id)) for https://$($cfg.domain)"

# --- keep DuckDNS pointed at our current public IP --------------------------
while ($true) {
    if ($cfg.duckdns_token) {
        $name = ($cfg.domain -split '\.')[0]
        try {
            $r = Invoke-RestMethod "https://www.duckdns.org/update?domains=$name&token=$($cfg.duckdns_token)&ip="
            if ($r -ne "OK") { Log "duckdns update: $r" }
        } catch { Log "duckdns update failed: $_" }
    }
    foreach ($p in @($relay, $agent, $caddy)) {
        if ($p.HasExited) { Log "process $($p.Id) exited, restarting everything"; exit 1 }  # task scheduler restarts us
    }
    Start-Sleep 300
}
