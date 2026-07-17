"""Shared pytest fixtures for the forwarder's test suite.

Env vars are set before `app` is imported, since app.py reads required secrets from os.environ at
module import time (os.environ["..."] raises KeyError if unset) — a real config error on the VPS,
but something the test suite has to fake to import the module at all.
"""
import os

os.environ.setdefault("FIELDNODE_DEVICE_TOKEN", "test-device-token")
os.environ.setdefault("OPEN_BRAIN_MCP_URL", "https://brain.example.test/mcp")
os.environ.setdefault("OPEN_BRAIN_KEY", "test-brain-key")

import httpx  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as app_module  # noqa: E402


@pytest.fixture(autouse=True)
def _reset_module_state():
    """Rate-limit buckets and the cached engcrm JWT are module-level dicts that would otherwise leak
    state between tests, making results order-dependent."""
    app_module._rate_limit_buckets.clear()
    app_module._engcrm_jwt["value"] = None
    app_module._engcrm_jwt["ts"] = 0.0
    yield


@pytest.fixture
def client() -> TestClient:
    return TestClient(app_module.app)


@pytest.fixture
def device_token() -> str:
    return app_module.DEVICE_TOKEN


def make_response(status_code: int, json_body=None, text_body: str | None = None) -> httpx.Response:
    """Build a real httpx.Response for a mocked outbound call — real enough that
    response.raise_for_status() / response.json() / response.text all behave normally."""
    request = httpx.Request("POST", "https://upstream.example.test/")
    if json_body is not None:
        return httpx.Response(status_code=status_code, json=json_body, request=request)
    return httpx.Response(status_code=status_code, content=(text_body or "").encode(), request=request)
