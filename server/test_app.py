"""Unit + integration tests for the forwarder's FastAPI routes.

Every outbound network call (engcrm, DeepSeek, Open Brain) is mocked via monkeypatch — these are
unit/integration tests of the forwarder's own request handling, not of the upstream services.
"""
import json

import httpx
import pytest

import app as app_module
from conftest import make_response

BAD_TOKEN = "wrong-token"


# --- liveness ----------------------------------------------------------------------------------


def test_health_returns_ok(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"ok": True, "service": "fieldnode-forwarder"}


def test_root_is_also_a_liveness_check(client):
    assert client.get("/").status_code == 200


# --- auth: shared across every token-gated endpoint ---------------------------------------------


@pytest.mark.parametrize(
    "method,path",
    [("post", "/capture"), ("post", "/agent"), ("post", "/ack"), ("get", "/nearby?lat=1&lng=1")],
)
def test_token_gated_endpoints_reject_missing_token(client, method, path):
    response = getattr(client, method)(path)
    assert response.status_code == 401


@pytest.mark.parametrize(
    "method,path",
    [("post", "/capture"), ("post", "/agent"), ("post", "/ack"), ("get", "/nearby?lat=1&lng=1")],
)
def test_token_gated_endpoints_reject_wrong_token(client, method, path):
    response = getattr(client, method)(path, headers={"X-Device-Token": BAD_TOKEN})
    assert response.status_code == 401


# --- /capture ------------------------------------------------------------------------------------


def test_capture_forwards_text_to_open_brain(client, device_token, monkeypatch):
    calls = []

    async def fake_post(self, url, *, json=None, headers=None, **kwargs):
        calls.append({"url": url, "json": json, "headers": headers})
        return make_response(200, json_body={"result": {"ok": True}})

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.post(
        "/capture",
        json={"text": "buy oat milk"},
        headers={"X-Device-Token": device_token, "X-Correlation-Id": "trace-123"},
    )

    assert response.status_code == 200
    assert response.json() == {"ok": True}
    assert len(calls) == 1
    assert calls[0]["url"] == app_module.BRAIN_URL
    assert calls[0]["json"]["params"]["arguments"]["content"] == "buy oat milk"
    # The phone's correlation id is adopted, not replaced, and forwarded upstream.
    assert calls[0]["headers"]["X-Correlation-Id"] == "trace-123"


def test_capture_rejects_empty_text(client, device_token):
    response = client.post("/capture", json={"text": "   "}, headers={"X-Device-Token": device_token})
    assert response.status_code == 400


def test_capture_rejects_invalid_json(client, device_token):
    response = client.post(
        "/capture",
        content=b"not json",
        headers={"X-Device-Token": device_token, "Content-Type": "application/json"},
    )
    assert response.status_code == 400


def test_capture_open_brain_failure_returns_generic_502_not_upstream_detail(client, device_token, monkeypatch):
    async def fake_post(self, url, *, json=None, headers=None, **kwargs):
        return make_response(502, text_body="Open Brain internal stack trace: secret-hostname.internal")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.post("/capture", json={"text": "x"}, headers={"X-Device-Token": device_token})

    assert response.status_code == 502
    assert response.json()["detail"] == "upstream service error"
    assert "secret-hostname" not in response.text


def test_capture_body_too_large_is_rejected(client, device_token):
    oversized = json.dumps({"text": "x" * 70_000})
    response = client.post(
        "/capture",
        content=oversized,
        headers={"X-Device-Token": device_token, "Content-Type": "application/json"},
    )
    assert response.status_code == 413


# --- /agent --------------------------------------------------------------------------------------


def test_agent_returns_503_when_llm_not_configured(client, device_token, monkeypatch):
    monkeypatch.setattr(app_module, "LLM_API_KEY", "")
    response = client.post("/agent", json={"messages": []}, headers={"X-Device-Token": device_token})
    assert response.status_code == 503


def test_agent_proxies_to_llm_and_returns_assistant_message(client, device_token, monkeypatch):
    monkeypatch.setattr(app_module, "LLM_API_KEY", "fake-llm-key")
    calls = []

    async def fake_post(self, url, *, json=None, headers=None, **kwargs):
        calls.append({"url": url, "json": json, "headers": headers})
        return make_response(
            200,
            json_body={"choices": [{"message": {"role": "assistant", "content": "hi"}}]},
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.post(
        "/agent",
        json={"messages": [{"role": "user", "content": "hello"}]},
        headers={"X-Device-Token": device_token},
    )

    assert response.status_code == 200
    assert response.json() == {"role": "assistant", "content": "hi"}
    assert calls[0]["url"] == f"{app_module.LLM_BASE_URL}/chat/completions"
    assert calls[0]["json"]["model"] == app_module.AGENT_MODEL
    assert "Bearer fake-llm-key" == calls[0]["headers"]["Authorization"]


def test_agent_llm_failure_does_not_leak_upstream_text(client, device_token, monkeypatch):
    monkeypatch.setattr(app_module, "LLM_API_KEY", "fake-llm-key")

    async def fake_post(self, url, *, json=None, headers=None, **kwargs):
        return make_response(500, text_body="stack trace with api key sk-abc123")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.post("/agent", json={"messages": []}, headers={"X-Device-Token": device_token})

    assert response.status_code == 502
    assert response.json()["detail"] == "upstream service error"
    assert "sk-abc123" not in response.text


def test_agent_body_too_large_is_rejected(client, device_token, monkeypatch):
    monkeypatch.setattr(app_module, "LLM_API_KEY", "fake-llm-key")
    oversized = json.dumps({"messages": [{"role": "user", "content": "x" * (3 * 1024 * 1024)}]})
    response = client.post(
        "/agent",
        content=oversized,
        headers={"X-Device-Token": device_token, "Content-Type": "application/json"},
    )
    assert response.status_code == 413


# --- /ack ----------------------------------------------------------------------------------------


def test_ack_records_and_echoes_the_body(client, device_token):
    response = client.post("/ack", content=b"approved:draft-7", headers={"X-Device-Token": device_token})
    assert response.status_code == 200
    assert response.json() == {"ok": True, "ack": "approved:draft-7"}


# --- /nearby ---------------------------------------------------------------------------------------


def test_nearby_returns_503_when_engcrm_not_configured(client, device_token):
    response = client.get("/nearby?lat=1&lng=1", headers={"X-Device-Token": device_token})
    assert response.status_code == 503


def _configure_engcrm(monkeypatch):
    monkeypatch.setattr(app_module, "ENGCRM_URL", "https://engcrm.example.test")
    monkeypatch.setattr(app_module, "ENGCRM_EMAIL", "svc@example.test")
    monkeypatch.setattr(app_module, "ENGCRM_PASSWORD", "secret")


def test_nearby_fetches_and_reshapes_leads(client, device_token, monkeypatch):
    _configure_engcrm(monkeypatch)

    async def fake_post(self, url, *, json=None, **kwargs):
        return make_response(200, json_body={"token": "jwt-1"})

    async def fake_get(self, url, *, params=None, headers=None, **kwargs):
        return make_response(
            200,
            json_body=[
                {"name": "Acme", "type": "hardware", "city": "Munich", "distance_m": 12.7,
                 "phone": "+49", "maps_uri": "geo:1,1", "latitude": 48.1, "longitude": 11.5},
            ],
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)

    response = client.get("/nearby?lat=48.1&lng=11.5", headers={"X-Device-Token": device_token})

    assert response.status_code == 200
    leads = response.json()["leads"]
    assert len(leads) == 1
    assert leads[0]["name"] == "Acme"
    assert leads[0]["distance_m"] == 13  # rounded


def test_nearby_refreshes_token_once_on_401_then_retries(client, device_token, monkeypatch):
    _configure_engcrm(monkeypatch)
    token_requests = []
    get_requests = []

    async def fake_post(self, url, *, json=None, **kwargs):
        token_requests.append(json)
        return make_response(200, json_body={"token": f"jwt-{len(token_requests)}"})

    async def fake_get(self, url, *, params=None, headers=None, **kwargs):
        get_requests.append(headers["Authorization"])
        if len(get_requests) == 1:
            return make_response(401, text_body="expired")
        return make_response(200, json_body=[])

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)

    response = client.get("/nearby?lat=1&lng=1", headers={"X-Device-Token": device_token})

    assert response.status_code == 200
    assert len(token_requests) == 2  # one initial fetch, one forced refresh after the 401
    assert len(get_requests) == 2
    assert get_requests[0] != get_requests[1]  # the retry used the refreshed token


def test_nearby_engcrm_failure_does_not_leak_upstream_detail(client, device_token, monkeypatch):
    _configure_engcrm(monkeypatch)

    async def fake_post(self, url, *, json=None, **kwargs):
        return make_response(500, text_body="engcrm internal error with secrets")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.get("/nearby?lat=1&lng=1", headers={"X-Device-Token": device_token})

    assert response.status_code == 502
    assert response.json()["detail"] == "upstream service error"
    assert "secrets" not in response.text


# --- rate limiting ---------------------------------------------------------------------------------


def test_rate_limit_trips_after_threshold_even_with_a_bad_token(client):
    for _ in range(app_module._RATE_LIMIT_MAX_REQUESTS):
        response = client.post("/ack", headers={"X-Device-Token": BAD_TOKEN})
        assert response.status_code == 401

    tripped = client.post("/ack", headers={"X-Device-Token": BAD_TOKEN})
    assert tripped.status_code == 429


def test_rate_limit_is_scoped_per_client_not_global(client, device_token):
    for _ in range(app_module._RATE_LIMIT_MAX_REQUESTS):
        client.post("/ack", headers={"X-Device-Token": device_token, "X-Forwarded-For": "10.0.0.1"})

    # A different client identity has its own untouched bucket.
    response = client.post(
        "/ack", headers={"X-Device-Token": device_token, "X-Forwarded-For": "10.0.0.2"},
    )
    assert response.status_code == 200


# --- correlation id ---------------------------------------------------------------------------------


def test_correlation_id_is_minted_when_absent(client, device_token, monkeypatch):
    async def fake_post(self, url, *, json=None, headers=None, **kwargs):
        assert headers["X-Correlation-Id"]  # non-empty, minted server-side
        return make_response(200, json_body={"result": {}})

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    response = client.post("/capture", json={"text": "x"}, headers={"X-Device-Token": device_token})
    assert response.status_code == 200
