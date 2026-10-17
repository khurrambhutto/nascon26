import base64
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

from app.common import sign


def test_health_reset_login_and_mobile_lists(client, login):
    assert client.get("/health").json() == {"status": "ok"}
    assert sign("evt-1", {"team_id": "t1", "event_type": "goal"}) == "972a8b558690b19121cc5bea84978f85e8eb15a3735018db36616bab1d7ec501"
    headers = login()
    assert client.get("/api/v1/me", headers=headers).json()["user"]["role"] == "club_manager"
    assert len(client.get("/api/v1/leagues", headers=headers).json()) == 2
    fixtures = client.get("/api/v1/fixtures?league_id=league-alpha-1", headers=headers).json()
    assert len(fixtures) == 6
    assert {m["status"] for m in fixtures} == {"completed", "upcoming", "in_progress"}
    assert client.get("/api/v1/matches/demo-m3", headers=headers).json()["pinned_product"]["product_id"] == "prod-jersey-001"
    assert len(client.get("/api/v1/clubs/club-alpha-1", headers=headers).json()["roster"]) == 5
    assert len(client.get("/api/v1/products", headers=headers).json()) == 3
    assert client.get("/api/v1/standings/league-alpha-1", headers=headers).status_code == 200
    assert client.get("/api/v1/sports/football", headers=headers).json()["scoring_model"] == "event"


def test_scoring_compensation_and_idempotency(client):
    body = {"event_id": "test-score", "match_id": "demo-m3", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}}
    assert client.post("/audit/v1/events", json=body).status_code == 201
    assert client.post("/audit/v1/events", json=body).status_code == 200
    assert client.post("/audit/v1/events", json={**body, "payload": {"team_id": "demo-club-4", "event_type": "goal"}}).status_code == 409
    response = client.post("/audit/v1/events", json={"event_id": "undo", "match_id": "demo-m3", "type": "compensation", "payload": {"compensates": "test-score", "reason": "Referee correction"}})
    assert response.status_code == 201
    score = client.get("/audit/v1/score/demo-m3").json()
    assert score["home"]["score"] == 1
    assert next(e for e in score["events"] if e["event_id"] == "test-score")["compensated"] is True


def test_checkout_twenty_concurrent_buyers(client):
    barrier = Barrier(20)
    def buy(i):
        barrier.wait()
        return client.post("/audit/v1/checkout", json={"order_id": "order-"+str(i), "customer_id": "buyer-"+str(i), "items": [{"variant_id": "var-jersey-001-M", "quantity": 1}], "payment": {"token": "tok_success"}})
    with ThreadPoolExecutor(max_workers=20) as pool:
        responses = list(pool.map(buy, range(20)))
    assert sorted(r.status_code for r in responses) == [201] + [409]*19
    assert client.get("/audit/v1/inventory/var-jersey-001-M").json() == {"variant_id": "var-jersey-001-M", "stock": 0, "reserved": 0}
    success = next(r.json() for r in responses if r.status_code == 201)
    receipt = success["receipt"]
    assert base64.b64decode(receipt["pdf_base64"]).startswith(b"%PDF")
    assert receipt["signature"] == sign(success["order_id"], receipt["payload"])


def test_rbac_boundaries(client):
    tokens = client.post("/audit/v1/rbac-matrix").json()["tokens"]
    for role, token in tokens.items():
        headers = {"Authorization": "Bearer "+token}
        assert client.get("/audit/v1/rbac/platform/config", headers=headers).status_code == (200 if role == "super_admin" else 403)
        assert client.get("/audit/v1/rbac/clubs/club-beta-1", headers=headers).status_code == (200 if role == "super_admin" else 403)
    assert client.get("/audit/v1/rbac/platform/config").status_code == 401


def test_validation_and_rate_limiting(client):
    assert client.post("/audit/v1/events", json={}).status_code == 400
    assert client.post("/audit/v1/reset", content=b"x"*(1024*1024+1)).status_code == 413
    responses = [client.get("/api/v1/ping") for _ in range(150)]
    assert sum(r.status_code == 429 for r in responses) == 50
    assert client.get("/health").status_code == 200


def test_offline_replay(client):
    body = {"device_id": "device-1", "actions": [{"action_id": "a1", "type": "score_event", "queued_at": "2026-10-17T11:00:00Z", "base_version": 0, "payload": {"event_id": "offline-1", "match_id": "demo-m3", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}}}]}
    response = client.post("/audit/v1/offline-sync", json=body)
    assert response.status_code == 200, response.text
    assert response.json()["results"][0]["resolution"] == "rebased_append"
    assert client.post("/audit/v1/offline-sync", json=body).json()["summary"]["duplicate"] == 1


def test_rate_limit_expires_and_does_not_affect_audit(client, monkeypatch):
    from types import SimpleNamespace
    from app import main
    current = [100.0]
    monkeypatch.setattr(main, "time", SimpleNamespace(monotonic=lambda: current[0]))
    for _ in range(100):
        assert client.get("/api/v1/ping").status_code == 200
    response = client.get("/api/v1/ping")
    assert response.status_code == 429 and response.headers["Retry-After"] == "10"
    assert client.post("/audit/v1/rbac-matrix").status_code == 200
    current[0] += 11
    assert client.get("/api/v1/ping").status_code == 200
