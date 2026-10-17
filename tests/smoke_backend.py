"""Live Docker/PostgreSQL acceptance probe. Run against a disposable demo stack.

WARNING: this intentionally resets the server's competition data.
Usage: python tests/smoke_backend.py [http://localhost:8080]
"""
import base64
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"


def request(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    try:
        response = urlopen(Request(BASE + path, data=data, headers=headers, method=method), timeout=10)
    except HTTPError as exc:
        response = exc
    return response.status, json.load(response)


def main():
    assert request("GET", "/health") == (200, {"status": "ok"})
    assert request("POST", "/audit/v1/reset", {})[0] == 200
    assert request("POST", "/audit/v1/reset", {})[0] == 200
    status, login = request("POST", "/api/v1/auth/login", {"email": "manager@nascon.test", "password": "Nascon@26"})
    assert status == 200
    token = login["token"]
    for route in ("/api/v1/leagues", "/api/v1/fixtures?league_id=league-alpha-1", "/api/v1/matches/demo-m3", "/api/v1/standings/league-alpha-1", "/api/v1/clubs/club-alpha-1", "/api/v1/products", "/api/v1/sports/football"):
        assert request("GET", route, token=token)[0] == 200, route
    assert request("GET", "/api/v1/clubs/club-beta-1", token=token)[0] == 403
    barrier = Barrier(20)

    def checkout(i):
        barrier.wait()
        return request("POST", "/audit/v1/checkout", {"order_id": f"smoke-order-{i}", "customer_id": f"smoke-customer-{i}", "items": [{"variant_id": "var-jersey-001-M", "quantity": 1}], "payment": {"token": "tok_success"}})

    start = time.monotonic()
    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(checkout, range(20)))
    elapsed = time.monotonic() - start
    assert elapsed < 10, elapsed
    assert sorted(status for status, _ in results) == [201] + [409] * 19
    assert request("GET", "/audit/v1/inventory/var-jersey-001-M")[1] == {"variant_id": "var-jersey-001-M", "stock": 0, "reserved": 0}
    receipt = next(body["receipt"] for status, body in results if status == 201)
    assert base64.b64decode(receipt["pdf_base64"]).startswith(b"%PDF")
    sync = {"device_id": "smoke-device", "actions": [{"action_id": "smoke-action", "type": "score_event", "queued_at": "2026-10-17T11:00:00Z", "base_version": 0, "payload": {"event_id": "smoke-event", "match_id": "demo-m3", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}}}]}
    assert request("POST", "/audit/v1/offline-sync", sync)[1]["summary"]["applied"] == 1
    assert request("POST", "/audit/v1/offline-sync", sync)[1]["summary"]["duplicate"] == 1
    print(json.dumps({"health": "ok", "mobile_contract": "ok", "tenant_isolation": "ok", "concurrent_buyers": 20, "successful_orders": 1, "checkout_burst_seconds": round(elapsed, 3), "pdf_receipt": "ok", "offline_replay": "ok"}, indent=2))


if __name__ == "__main__":
    main()
