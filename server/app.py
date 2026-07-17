"""
Fieldnode fleet forwarder — a thin, authenticated bridge between the phone and Open Brain.

The phone authenticates with a device token (the ONLY secret it holds). This service holds the Open
Brain access key server-side and forwards captures to Open Brain's `capture_thought`, which does the
triage/enrichment + embedding itself. If the phone is ever compromised, the device token is revocable
and can only post captures — the Open Brain key never leaves the server.
"""
import hmac
import json
import logging
import os
import time
import uuid
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool

import maprender

DEVICE_TOKEN = os.environ["FIELDNODE_DEVICE_TOKEN"]
BRAIN_URL = os.environ["OPEN_BRAIN_MCP_URL"]
BRAIN_KEY = os.environ["OPEN_BRAIN_KEY"]
PROJECT = os.environ.get("FIELDNODE_PROJECT", "fieldnode")

# Optional on-site awareness (v2b): the forwarder asks engcrm "what leads are near this location?"
# holding the engcrm service credential server-side. Unset -> /nearby returns 503.
ENGCRM_URL = os.environ.get("ENGCRM_URL", "").rstrip("/")
ENGCRM_EMAIL = os.environ.get("ENGCRM_EMAIL", "")
ENGCRM_PASSWORD = os.environ.get("ENGCRM_PASSWORD", "")
_engcrm_jwt = {"value": None, "ts": 0.0}

# On-phone agent (v3): the forwarder proxies an OpenAI-compatible LLM (DeepSeek) holding the API key.
# The phone runs the agent loop and executes the tools; this endpoint is a stateless completion proxy.
LLM_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
LLM_BASE_URL = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
AGENT_MODEL = os.environ.get("AGENT_MODEL", "deepseek-v4-flash")

app = FastAPI(title="fieldnode-forwarder")


# --- structured logging (coding-standards 7.1-7.5: JSON, ISO 8601, correlation id) ---------------


class StructuredFormatter(logging.Formatter):
    """JSON formatter with ISO 8601 timestamps and an optional correlation id per record."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "source": f"{record.module}.{record.funcName}",
            "message": record.getMessage(),
        }
        correlation_id = getattr(record, "correlation_id", None)
        if correlation_id:
            entry["correlation_id"] = correlation_id
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry)


logger = logging.getLogger("fieldnode-forwarder")
logger.setLevel(logging.INFO)
_handler = logging.StreamHandler()
_handler.setFormatter(StructuredFormatter())
logger.addHandler(_handler)
logger.propagate = False


def _correlation_id(request: Request) -> str:
    """Adopt the caller's trace id if it sent one (dataflow tracing across the phone -> forwarder ->
    backend hop); otherwise mint one here, since the forwarder is this unit of work's entry point."""
    return request.headers.get("X-Correlation-Id") or uuid.uuid4().hex


def _log_entry(endpoint: str, correlation_id: str) -> None:
    logger.info(f"{endpoint} request accepted", extra={"correlation_id": correlation_id})


def _log_exit(endpoint: str, correlation_id: str, outcome: str) -> None:
    logger.info(f"{endpoint} {outcome}", extra={"correlation_id": correlation_id})


def _upstream_failure(correlation_id: str, detail: str) -> HTTPException:
    """Log the real upstream failure detail server-side; never hand it to the client (coding-standards
    6.4 — an error message must not expose internal system details). Detail can carry internal
    hostnames, stack fragments, or upstream response bodies."""
    logger.warning(f"upstream failure: {detail}", extra={"correlation_id": correlation_id})
    return HTTPException(status_code=502, detail="upstream service error")


# --- rate limiting + body-size caps (coding-standards 13.4) ---------------------------------------

_RATE_LIMIT_WINDOW_SECONDS = 60
_RATE_LIMIT_MAX_REQUESTS = 30
_rate_limit_buckets: dict[str, list[float]] = {}


def _client_ip(request: Request) -> str:
    """Best-effort client identity for rate limiting. The forwarder is reachable ONLY via the local
    Apache reverse proxy (container binds 127.0.0.1 only — see docker-compose.yml), which sets
    X-Forwarded-For itself, so that header is trusted here: nothing external can reach this process
    to spoof it directly. Falls back to the raw socket peer for local/dev use without the proxy."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _enforce_rate_limit(request: Request) -> None:
    """Simple in-memory fixed-window limiter, keyed by client IP. Runs BEFORE the token check so a
    credential-guessing burst is throttled too, not just successful requests (coding-standards 13.4).
    Single-process assumption (the forwarder runs one uvicorn worker) — adequate for a personal,
    single-device-token service; swap for a shared (e.g. Redis-backed) limiter behind multiple workers."""
    client_ip = _client_ip(request)
    now = time.time()
    window_start = now - _RATE_LIMIT_WINDOW_SECONDS
    recent = [ts for ts in _rate_limit_buckets.get(client_ip, []) if ts > window_start]
    if len(recent) >= _RATE_LIMIT_MAX_REQUESTS:
        _rate_limit_buckets[client_ip] = recent
        raise HTTPException(status_code=429, detail="rate limit exceeded")
    recent.append(now)
    _rate_limit_buckets[client_ip] = recent


def _enforce_body_size(request: Request, max_bytes: int) -> None:
    """Reject an oversized request by its declared Content-Length before we read the body. A missing
    or malformed header falls through to the downstream body read, which fails on its own terms."""
    content_length = request.headers.get("content-length")
    if content_length is None:
        return
    try:
        declared_bytes = int(content_length)
    except ValueError:
        return
    if declared_bytes > max_bytes:
        raise HTTPException(status_code=413, detail="request body too large")


_MAX_CAPTURE_BODY_BYTES = 64 * 1024        # a text capture; images aren't dispatched yet (README)
_MAX_AGENT_BODY_BYTES = 2 * 1024 * 1024    # bounded by the phone's own AgentSession.MAX_SESSION_CHARS
_MAX_ACK_BODY_BYTES = 8 * 1024             # a one-tap notification-action acknowledgement


# --- liveness --------------------------------------------------------------------------------------


@app.get("/")
@app.get("/health")
def health() -> dict:
    # Root is a liveness check too, so the uptime watchdog (which probes "/") sees a 200.
    return {"ok": True, "service": "fieldnode-forwarder"}


# --- auth ------------------------------------------------------------------------------------------


def _require_token(request: Request) -> None:
    presented_token = request.headers.get("X-Device-Token", "")
    if not hmac.compare_digest(presented_token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="bad device token")


def _require_engcrm() -> None:
    if not (ENGCRM_URL and ENGCRM_EMAIL and ENGCRM_PASSWORD):
        raise HTTPException(status_code=503, detail="engcrm not configured")


# --- on-site awareness (v2b): engcrm-backed lead lookup --------------------------------------------


async def _engcrm_token(client: httpx.AsyncClient, force: bool = False) -> str:
    """Cached engcrm JWT (24h expiry); refreshed on demand or when forced after a 401."""
    if not force and _engcrm_jwt["value"] and (time.time() - _engcrm_jwt["ts"] < 23 * 3600):
        return _engcrm_jwt["value"]
    response = await client.post(
        f"{ENGCRM_URL}/api/auth/token",
        json={"email": ENGCRM_EMAIL, "password": ENGCRM_PASSWORD},
    )
    response.raise_for_status()
    token = response.json()["token"]
    _engcrm_jwt["value"] = token
    _engcrm_jwt["ts"] = time.time()
    return token


async def _fetch_leads(lat: float, lng: float, limit: int, correlation_id: str) -> list:
    """Raw engcrm recon leads near (lat,lng), with a one-shot token refresh on 401. Shared by the
    JSON list (/nearby) and the rendered map (/nearby/map.png)."""
    params = {"lat": lat, "lng": lng, "limit": max(1, min(limit, 50))}
    headers_base = {"X-Correlation-Id": correlation_id}
    async with httpx.AsyncClient(timeout=20) as client:
        token = await _engcrm_token(client)
        response = await client.get(
            f"{ENGCRM_URL}/api/recon", params=params,
            headers={**headers_base, "Authorization": f"Bearer {token}"},
        )
        if response.status_code == 401:  # token expired/revoked — refresh once and retry
            token = await _engcrm_token(client, force=True)
            response = await client.get(
                f"{ENGCRM_URL}/api/recon", params=params,
                headers={**headers_base, "Authorization": f"Bearer {token}"},
            )
        response.raise_for_status()
    return response.json()


@app.get("/nearby")
async def nearby(request: Request, lat: float, lng: float, limit: int = 10) -> dict:
    correlation_id = _correlation_id(request)
    _enforce_rate_limit(request)
    _require_token(request)
    _require_engcrm()
    _log_entry("/nearby", correlation_id)
    try:
        leads = await _fetch_leads(lat, lng, limit, correlation_id)
    except httpx.HTTPError as error:
        raise _upstream_failure(correlation_id, f"engcrm error on /nearby: {error}")

    _log_exit("/nearby", correlation_id, f"{len(leads)} leads")
    return {
        "leads": [
            {
                "name": lead.get("name"),
                "type": lead.get("type"),
                "city": lead.get("city"),
                "distance_m": round(lead.get("distance_m") or 0),
                "phone": lead.get("phone"),
                "maps_uri": lead.get("maps_uri"),
                "lat": lead.get("latitude"),
                "lng": lead.get("longitude"),
            }
            for lead in leads
        ],
    }


@app.get("/nearby/map.png")
async def nearby_map(request: Request, lat: float, lng: float, limit: int = 10) -> Response:
    # A static map of the leads near (lat,lng): the phone's location + a numbered pin per lead, rendered
    # server-side from OpenStreetMap tiles (no map API key). Pin numbers match /nearby's order.
    correlation_id = _correlation_id(request)
    _enforce_rate_limit(request)
    _require_token(request)
    _require_engcrm()
    _log_entry("/nearby/map.png", correlation_id)
    try:
        leads = await _fetch_leads(lat, lng, limit, correlation_id)
    except httpx.HTTPError as error:
        raise _upstream_failure(correlation_id, f"engcrm error on /nearby/map.png: {error}")

    points = [
        (lead["latitude"], lead["longitude"])
        for lead in leads
        if lead.get("latitude") is not None and lead.get("longitude") is not None
    ]
    png = await run_in_threadpool(maprender.render_leads_map, (lat, lng), points)
    _log_exit("/nearby/map.png", correlation_id, f"rendered {len(points)} points")
    return Response(content=png, media_type="image/png", headers={"Cache-Control": "no-store"})


# --- on-phone agent (v3): stateless LLM completion proxy --------------------------------------------


@app.post("/agent")
async def agent(request: Request) -> dict:
    # One LLM turn for the on-phone agent. Body: {messages: [...], tools: [...]?}. Returns the
    # assistant message ({role, content, tool_calls?}); the phone executes tools and loops.
    correlation_id = _correlation_id(request)
    _enforce_rate_limit(request)
    _enforce_body_size(request, _MAX_AGENT_BODY_BYTES)
    _require_token(request)
    if not LLM_API_KEY:
        raise HTTPException(status_code=503, detail="agent LLM not configured")
    _log_entry("/agent", correlation_id)

    body = await request.json()
    payload = {
        "model": AGENT_MODEL,
        "messages": body.get("messages", []),
        "temperature": body.get("temperature", 0.3),
    }
    if body.get("tools"):
        payload["tools"] = body["tools"]

    try:
        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.post(
                f"{LLM_BASE_URL}/chat/completions", json=payload,
                headers={"Authorization": f"Bearer {LLM_API_KEY}", "X-Correlation-Id": correlation_id},
            )
    except httpx.RequestError as error:
        raise _upstream_failure(correlation_id, f"llm unreachable: {error}")
    if response.status_code // 100 != 2:
        raise _upstream_failure(correlation_id, f"llm {response.status_code}: {response.text[:200]}")

    _log_exit("/agent", correlation_id, "completion returned")
    return response.json()["choices"][0]["message"]


# --- notification action callback -------------------------------------------------------------------


@app.post("/ack")
async def ack(request: Request) -> dict:
    # Notification action callback (one-tap approve/dismiss). The phone authenticates with its device
    # token; the body says what was acknowledged. A real fleet would act on it (approve a draft, trigger
    # a deploy…); here we record it so the round-trip is observable.
    correlation_id = _correlation_id(request)
    _enforce_rate_limit(request)
    _enforce_body_size(request, _MAX_ACK_BODY_BYTES)
    _require_token(request)
    body = (await request.body()).decode("utf-8", "replace")
    logger.info(f"ack received: {body}", extra={"correlation_id": correlation_id})
    return {"ok": True, "ack": body}


# --- capture -------------------------------------------------------------------------------------


async def _forward_to_open_brain(text: str, correlation_id: str) -> None:
    """POST one capture to Open Brain's capture_thought tool. Raises HTTPException on any failure,
    with the real detail logged server-side only (coding-standards 6.4)."""
    rpc = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {
            "name": "capture_thought",
            "arguments": {"content": text, "project": PROJECT},
        },
    }
    headers = {
        "Authorization": f"Bearer {BRAIN_KEY}",
        "x-brain-key": BRAIN_KEY,
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "X-Correlation-Id": correlation_id,
    }

    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.post(BRAIN_URL, json=rpc, headers=headers)
    except httpx.RequestError as error:
        raise _upstream_failure(correlation_id, f"open brain unreachable: {error}")

    if response.status_code // 100 != 2:
        raise _upstream_failure(correlation_id, f"open brain HTTP {response.status_code}: {response.text[:200]}")

    result = _extract_json(response)
    if isinstance(result, dict) and result.get("error"):
        raise _upstream_failure(correlation_id, f"open brain error: {result['error']}")


@app.post("/capture")
async def capture(request: Request) -> dict:
    correlation_id = _correlation_id(request)
    _enforce_rate_limit(request)
    _enforce_body_size(request, _MAX_CAPTURE_BODY_BYTES)
    _require_token(request)

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid JSON")

    text = (body.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="empty capture")

    _log_entry("/capture", correlation_id)
    await _forward_to_open_brain(text, correlation_id)
    _log_exit("/capture", correlation_id, "forwarded to open brain")
    return {"ok": True}


def _extract_json(response: httpx.Response):
    """Open Brain may answer as plain JSON or as an SSE stream — handle both."""
    content_type = response.headers.get("content-type", "")
    if "text/event-stream" in content_type:
        for line in response.text.splitlines():
            if line.startswith("data:"):
                fragment = line[len("data:"):].strip()
                if fragment and fragment != "[DONE]":
                    try:
                        return json.loads(fragment)
                    except json.JSONDecodeError:
                        continue
        return None
    try:
        return response.json()
    except Exception:
        return None
