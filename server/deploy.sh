#!/usr/bin/env bash
# Deploy / update the forwarder on the VPS. Syncs code, rebuilds, restarts, health-checks.
# Secrets live only in $REMOTE/.env on the server (never synced, never committed).
# One-time setup (Apache vhost + certbot + .env) is in README.md — do that first.
set -euo pipefail

VPS="${FIELDNODE_VPS:-user@YOUR_VPS}"   # override: FIELDNODE_VPS=you@host ./deploy.sh
REMOTE=/opt/fieldnode-forwarder
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "▶ syncing code to $VPS:$REMOTE (excluding .env)…"
ssh "$VPS" "mkdir -p $REMOTE"
rsync -az --delete --exclude '.env' --exclude '__pycache__' "$HERE"/ "$VPS:$REMOTE/"

echo "▶ checking server has its .env…"
ssh "$VPS" "test -f $REMOTE/.env" || {
  echo "✗ $REMOTE/.env is missing. Create it from .env.example with the real secrets first." >&2
  exit 1
}

echo "▶ build + (re)start container…"
ssh "$VPS" "cd $REMOTE && docker compose up -d --build"

echo "▶ health check (on the VPS, localhost)…"
ssh "$VPS" "sleep 2 && curl -fsS http://127.0.0.1:8090/health && echo"

echo "✔ deployed. Public check: curl https://fieldnode.example.com/health"
