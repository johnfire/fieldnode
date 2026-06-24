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

import httpx
from fastapi import FastAPI, HTTPException, Request

DEVICE_TOKEN = os.environ["FIELDNODE_DEVICE_TOKEN"]
BRAIN_URL = os.environ["OPEN_BRAIN_MCP_URL"]
BRAIN_KEY = os.environ["OPEN_BRAIN_KEY"]
PROJECT = os.environ.get("FIELDNODE_PROJECT", "fieldnode")

app = FastAPI(title="fieldnode-forwarder")


@app.get("/")
@app.get("/health")
def health() -> dict:
    # Root is a liveness check too, so the uptime watchdog (which probes "/") sees a 200.
    return {"ok": True, "service": "fieldnode-forwarder"}


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
