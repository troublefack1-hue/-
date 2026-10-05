#!/usr/bin/env bash
# Installs the relay on Ubuntu/Debian. Run as root from the repo's pc-remote/ dir:
#   sudo bash relay/install.sh pc.example.duckdns.org
set -euo pipefail

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  echo "usage: install.sh <domain>"; exit 1
fi

SRC="$(cd "$(dirname "$0")/.." && pwd)"
DEST=/opt/pc-remote

apt-get update -qq
apt-get install -y -qq python3-venv curl debian-keyring debian-archive-keyring apt-transport-https

id -u pcremote &>/dev/null || useradd --system --home "$DEST" --shell /usr/sbin/nologin pcremote

mkdir -p "$DEST"
cp -r "$SRC/relay" "$SRC/web" "$DEST/"
python3 -m venv "$DEST/relay/venv"
"$DEST/relay/venv/bin/pip" install -q -r "$DEST/relay/requirements.txt"

if [[ ! -f "$DEST/relay/config.json" ]]; then
  SECRET="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"
  sed "s/CHANGE-ME-to-a-long-random-string-32-chars/$SECRET/" \
      "$DEST/relay/config.example.json" > "$DEST/relay/config.json"
  echo
  echo "=== Generated secret (also in $DEST/relay/config.json): ==="
  echo "$SECRET"
  echo
fi
chown -R pcremote:pcremote "$DEST"
chmod 600 "$DEST/relay/config.json"

cp "$DEST/relay/pc-remote-relay.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now pc-remote-relay

# Caddy = HTTPS with automatic certificates
if ! command -v caddy &>/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq && apt-get install -y -qq caddy
fi
sed "s/pc.example.duckdns.org/$DOMAIN/" "$DEST/relay/Caddyfile.example" > /etc/caddy/Caddyfile
systemctl reload caddy || systemctl restart caddy

echo "Relay is up: https://$DOMAIN"
echo "Open it on the phone and enter the secret."
