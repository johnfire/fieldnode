"""
Fieldnode fleet forwarder — a thin, authenticated bridge between the phone and Open Brain.

The phone authenticates with a device token (the ONLY secret it holds). This service holds the Open
Brain access key server-side and forwards captures to Open Brain's `capture_thought`, which does the
triage/enrichment + embedding itself. If the phone is ever compromised, the device token is revocable
and can only post captures — the Open Brain key never leaves the server.
"""
import hmac
import json
import os
import time

import httpx
from fastapi import FastAPI, HTTPException, Request

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


@app.get("/")
@app.get("/health")
def health() -> dict:
    # Root is a liveness check too, so the uptime watchdog (which probes "/") sees a 200.
    return {"ok": True, "service": "fieldnode-forwarder"}


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


@app.get("/nearby")
async def nearby(request: Request, lat: float, lng: float, limit: int = 10) -> dict:
    presented_token = request.headers.get("X-Device-Token", "")
    if not hmac.compare_digest(presented_token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="bad device token")
    if not (ENGCRM_URL and ENGCRM_EMAIL and ENGCRM_PASSWORD):
        raise HTTPException(status_code=503, detail="engcrm not configured")

    params = {"lat": lat, "lng": lng, "limit": max(1, min(limit, 50))}
    try:
        async with httpx.AsyncClient(timeout=20) as client:
            token = await _engcrm_token(client)
            response = await client.get(
                f"{ENGCRM_URL}/api/recon", params=params,
                headers={"Authorization": f"Bearer {token}"},
            )
            if response.status_code == 401:  # token expired/revoked — refresh once and retry
                token = await _engcrm_token(client, force=True)
                response = await client.get(
                    f"{ENGCRM_URL}/api/recon", params=params,
                    headers={"Authorization": f"Bearer {token}"},
                )
            response.raise_for_status()
    except httpx.HTTPError as error:
        raise HTTPException(status_code=502, detail=f"engcrm error: {error}")

    leads = response.json()
    return {
        "leads": [
            {
                "name": lead.get("name"),
                "type": lead.get("type"),
                "city": lead.get("city"),
                "distance_m": round(lead.get("distance_m") or 0),
                "phone": lead.get("phone"),
                "maps_uri": lead.get("maps_uri"),
            }
            for lead in leads
        ],
    }


@app.post("/agent")
async def agent(request: Request) -> dict:
    # One LLM turn for the on-phone agent. Body: {messages: [...], tools: [...]?}. Returns the
    # assistant message ({role, content, tool_calls?}); the phone executes tools and loops.
    presented_token = request.headers.get("X-Device-Token", "")
    if not hmac.compare_digest(presented_token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="bad device token")
    if not LLM_API_KEY:
        raise HTTPException(status_code=503, detail="agent LLM not configured")

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
                headers={"Authorization": f"Bearer {LLM_API_KEY}"},
            )
    except httpx.RequestError as error:
        raise HTTPException(status_code=502, detail=f"llm unreachable: {error}")
    if response.status_code // 100 != 2:
        raise HTTPException(status_code=502, detail=f"llm {response.status_code}: {response.text[:200]}")

    return response.json()["choices"][0]["message"]


@app.post("/ack")
async def ack(request: Request) -> dict:
    # Notification action callback (one-tap approve/dismiss). The phone authenticates with its device
    # token; the body says what was acknowledged. A real fleet would act on it (approve a draft, trigger
    # a deploy…); here we record it so the round-trip is observable.
    presented_token = request.headers.get("X-Device-Token", "")
    if not hmac.compare_digest(presented_token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="bad device token")
    body = (await request.body()).decode("utf-8", "replace")
    print(f"[ack] {body}", flush=True)
    return {"ok": True, "ack": body}


@app.post("/capture")
async def capture(request: Request) -> dict:
    presented_token = request.headers.get("X-Device-Token", "")
    if not hmac.compare_digest(presented_token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="bad device token")

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid JSON")

    text = (body.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="empty capture")

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
    }

    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.post(BRAIN_URL, json=rpc, headers=headers)
    except httpx.RequestError as error:
        raise HTTPException(status_code=502, detail=f"open brain unreachable: {error}")

    if response.status_code // 100 != 2:
        raise HTTPException(status_code=502, detail=f"open brain HTTP {response.status_code}")

    result = _extract_json(response)
    if isinstance(result, dict) and result.get("error"):
        raise HTTPException(status_code=502, detail=f"open brain error: {result['error']}")

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
