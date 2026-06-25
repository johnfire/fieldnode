# Notify-back (self-hosted ntfy)

The app's `NotifyService` streams from a self-hosted [ntfy](https://ntfy.sh) topic and raises a
notification for each message your fleet publishes (CI done, job finished, approval needed…). No
Google/FCM — your push channel, your server.

## Run ntfy

```bash
# on your server, in this dir:
docker compose up -d            # binds 127.0.0.1:8095 (put Apache/TLS in front, see ../apache-fieldnode.conf)

# lock it down (deny-all by default) and make a token scoped to your topic:
docker compose exec ntfy ntfy user add fleet              # set a password
docker compose exec ntfy ntfy access fleet 'fieldnode*' rw
docker compose exec ntfy ntfy token add fleet             # -> tk_... (use in fleet.config + publishers)
```

Edit `server.yml` and set `base-url` to your public ntfy URL.

## Publish from your fleet

```bash
curl -H "Authorization: Bearer tk_..." -H "Title: CI" \
     -d "build green on main" https://ntfy.example.com/fieldnode
```

## Point the phone at it

Add to the device's `Fieldnode/fleet.config`:

```
ntfy_url=https://ntfy.example.com
ntfy_topic=fieldnode
ntfy_token=tk_...
```

Then start the listener in the app (or it auto-starts on boot). On MIUI, enable Autostart +
battery "No restrictions" so the listener survives.
