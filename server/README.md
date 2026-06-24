# Fieldnode fleet forwarder

A thin authenticated bridge: **phone → (device token) → this service → (Open Brain key) → Open Brain
`capture_thought`** (which triages + embeds). The phone never holds the Open Brain key.

- `POST /capture` — header `X-Device-Token`; body `{id, kind, text, createdAt}`. Forwards `text` to
  Open Brain tagged with project `fieldnode`. → `{ok:true}` / 401 bad token / 400 empty / 502 upstream.
- `GET /health` — liveness.

Runs in Docker, bound to `127.0.0.1:8090`; Apache terminates TLS and reverse-proxies to it.

## One-time VPS setup

```bash
# 0. DNS: fieldnode.example.com -> YOUR_VPS_IP  (already in place)

# 1. Secrets (on the VPS):
mkdir -p /opt/fieldnode-forwarder
cp .env.example /opt/fieldnode-forwarder/.env   # then edit:
#   FIELDNODE_DEVICE_TOKEN=<openssl rand -hex 32>   (same value goes in the phone's fleet.config)
#   OPEN_BRAIN_KEY=<MCP_ACCESS_KEY from the password manager>

# 2. Apache vhost + modules + cert:
a2enmod proxy proxy_http ssl headers rewrite
cp apache-fieldnode.conf /etc/apache2/sites-available/fieldnode.conf
# install with the :80 block first (comment the :443 block), enable, reload, then certbot:
a2ensite fieldnode && systemctl reload apache2
certbot --apache -d fieldnode.example.com
# ensure the resulting :443 vhost has the ProxyPass lines (see apache-fieldnode.conf), reload.
```

## Deploy / update

```bash
./deploy.sh          # rsync + docker compose up -d --build + health check
```

## Verify end-to-end

```bash
curl https://fieldnode.example.com/health
curl -X POST https://fieldnode.example.com/capture \
  -H "X-Device-Token: <token>" -H "Content-Type: application/json" \
  -d '{"text":"hello from curl","kind":"TEXT"}'
# then confirm it appears in Open Brain (search_thoughts).
```
