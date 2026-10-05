# pc-remote, direct mode: white static IP, no third-party services.
# The relay itself serves HTTPS with a certificate from our own CA
# (see make_cert.py); the agent talks to it over localhost.
# The router must forward TCP <port> (default 8443) to this PC.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$cfg = Get-Content "$here\config.json" -Raw | ConvertFrom-Json
$py = "$root\agent\venv\Scripts\python.exe"
$log = "$here\run.log"
function Log($m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $log -Append }

# --- relay: plain on 127.0.0.1:8787 for the agent, TLS on 0.0.0.0:<port> for the phone
$env:PC_REMOTE_CONFIG = "$here\relay.json"
@{ secret = $cfg.secret; host = "127.0.0.1"; port = 8787; ntfy_wake_url = $cfg.ntfy_wake_url
   tls_host = "0.0.0.0"; tls_port = $cfg.port; tls_cert = "$here\server.crt"; tls_key = "$here\server.key"; ca_cert = "$here\ca.crt" } |
    ConvertTo-Json | Set-Content "$here\relay.json" -Encoding UTF8
$relay = Start-Process $py -ArgumentList "`"$root\relay\relay.py`"" -WorkingDirectory "$root\relay" -PassThru -WindowStyle Hidden `
    -RedirectStandardError "$here\relay.log"
Log "relay started (pid $($relay.Id)), https://$($cfg.public_ip):$($cfg.port)"

# --- agent
@{ relay_url = "http://127.0.0.1:8787"; secret = $cfg.secret; max_width = 1280; quality = 55; fps = 12; monitor = 1 } |
    ConvertTo-Json | Set-Content "$root\agent\config.json" -Encoding UTF8
$agent = Start-Process $py -ArgumentList "`"$root\agent\agent.py`"" -WorkingDirectory "$root\agent" -PassThru -WindowStyle Hidden
Log "agent started (pid $($agent.Id))"

while ($true) {
    Start-Sleep 30
    foreach ($p in @($relay, $agent)) {
        if ($p.HasExited) { Log "process $($p.Id) exited, restarting everything"; exit 1 }  # task scheduler restarts us
    }
}
