import time
import jwt
import pytest

from app.auth import secret


def test_complete_role_policy_matrix(client):
    tokens = client.post("/audit/v1/rbac-matrix").json()["tokens"]
    probes = [
        ("POST", "/audit/v1/rbac/tenants", {"super_admin"}),
        ("POST", "/audit/v1/rbac/leagues/league-alpha-1/fixtures", {"super_admin", "league_organizer"}),
        ("POST", "/audit/v1/rbac/leagues/league-alpha-2/fixtures", {"super_admin"}),
        ("POST", "/audit/v1/rbac/leagues/league-beta-1/fixtures", {"super_admin"}),
        ("PATCH", "/audit/v1/rbac/players/player-alpha-1", {"super_admin", "club_manager", "player"}),
        ("PATCH", "/audit/v1/rbac/players/player-alpha-2", {"super_admin"}),
        ("PATCH", "/audit/v1/rbac/players/player-beta-1", {"super_admin"}),
        ("GET", "/audit/v1/rbac/clubs/club-alpha-1", set(tokens)),
        ("GET", "/audit/v1/rbac/clubs/club-alpha-2", set(tokens)),
        ("GET", "/audit/v1/rbac/clubs/club-beta-1", {"super_admin"}),
        ("GET", "/audit/v1/rbac/platform/config", {"super_admin"}),
    ]
    for resource in ("roster", "products"):
        for club in ("club-alpha-1", "club-alpha-2", "club-beta-1"):
            probes.append(("POST", f"/audit/v1/rbac/clubs/{club}/{resource}", {"super_admin", "club_manager"} if club == "club-alpha-1" else {"super_admin"}))
    for role, token in tokens.items():
        for method, path, allowed in probes:
            response = client.request(method, path, headers={"Authorization": "Bearer "+token}, json={} if method != "GET" else None)
            assert response.status_code == (200 if role in allowed else 403), (role, path, response.text)
            if role not in allowed:
                assert response.json()["error"]["code"] == "FORBIDDEN"


@pytest.mark.parametrize("token", [None, "bad", "Bearer junk", "Basic junk"])
def test_missing_malformed_auth(client, token):
    headers = {"Authorization": token} if token else {}
    assert client.get("/audit/v1/rbac/platform/config", headers=headers).status_code == 401


def test_tampered_expired_and_none_tokens(client):
    tokens = client.post("/audit/v1/rbac-matrix").json()["tokens"]
    token = tokens["super_admin"]
    parts = token.split(".")
    parts[2] = ("a" if parts[2][0] != "a" else "b") + parts[2][1:]
    examples = [".".join(parts), jwt.encode({"sub": "u-admin", "iat": 0, "exp": int(time.time())-1}, secret(), algorithm="HS256"), jwt.encode({"sub": "u-admin", "iat": 0, "exp": int(time.time())+999}, "", algorithm="none"), jwt.encode({"sub": "missing", "iat": 0, "exp": int(time.time())+999}, secret(), algorithm="HS256")]
    for value in examples:
        assert client.get("/audit/v1/rbac/platform/config", headers={"Authorization": "Bearer "+value}).status_code == 401


def test_mobile_scope_and_pin_ownership(client, login):
    viewer, admin, organizer = login("viewer"), login("admin"), login("organizer")
    assert client.get("/api/v1/clubs/club-beta-1", headers=viewer).status_code == 403
    assert client.get("/api/v1/clubs/club-beta-1", headers=admin).status_code == 200
    assert len(client.get("/api/v1/leagues", headers=admin).json()) == 3
    assert client.get("/api/v1/fixtures?league_id=league-beta-1", headers=viewer).status_code == 403
    assert client.get("/api/v1/standings/league-beta-1", headers=viewer).status_code == 403
    assert client.get("/api/v1/clubs/missing", headers=admin).status_code == 404
    assert client.get("/api/v1/products").status_code == 401
    assert client.post("/api/v1/matches/demo-m1/pin", headers=viewer, json={"product_id": "prod-jersey-001"}).status_code == 403
    response = client.post("/api/v1/matches/demo-m1/pin", headers=organizer, json={"product_id": "prod-jersey-001"})
    assert response.status_code == 200 and response.json()["pinned_product"]["product_id"] == "prod-jersey-001"
    assert client.post("/api/v1/matches/demo-m1/pin", headers=organizer, json={"product_id": None}).json()["pinned_product"] is None
    assert client.get("/api/v1/sports", headers=viewer).status_code == 200
    assert client.post("/api/v1/auth/login", json={"email": "manager@nascon.test", "password": "wrong"}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"email": "missing@nascon.test", "password": "wrong"}).status_code == 401


def test_password_hashes_are_salted_and_independent_of_token_keys(client, monkeypatch):
    from app.auth import password_hash, verify_password
    first = password_hash("Nascon@26", "manager@nascon.test")
    second = password_hash("Nascon@26", "manager@nascon.test")
    assert first != second
    assert verify_password("Nascon@26", first)
    assert not verify_password("wrong", first)
    assert not verify_password("Nascon@26", "corrupt")
    assert not verify_password("Nascon@26", "wrong$120000$00$00")
    monkeypatch.setenv("JWT_SECRET", "a-new-random-test-token-secret-that-is-long")
    response = client.post("/api/v1/auth/login", json={"email": "manager@nascon.test", "password": "Nascon@26"})
    assert response.status_code == 200
