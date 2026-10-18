import pytest


def league_body(sport="basketball"):
    return {"name": "Campus " + sport, "sport_id": sport,
            "teams": ["club-alpha-1", "club-alpha-2"]}


def match_body(league="league-alpha-1"):
    return {"league_id": league, "home_team_id": "club-alpha-1",
            "away_team_id": "club-alpha-2", "scheduled_at": "2026-10-19T10:00:00Z",
            "venue": "Main court"}


@pytest.mark.parametrize("sport,event,points", [("football", "goal", 1),
    ("basketball", "three_pointer", 3), ("cricket", "six", 6), ("esports", "round_win", 1)])
def test_create_other_sport_league_match_and_score(client, login, sport, event, points):
    headers = login("admin")
    response = client.post("/api/v1/leagues", json=league_body(sport), headers=headers)
    assert response.status_code == 201, response.text
    league = response.json()["league_id"]
    response = client.post("/api/v1/matches", json=match_body(league), headers=headers)
    assert response.status_code == 201, response.text
    match = response.json()["match_id"]
    fixtures = client.get("/api/v1/fixtures", params={"league_id": league}, headers=headers).json()
    assert fixtures[0]["match_id"] == match
    assert fixtures[0]["sport_id"] == sport
    for eid, kind, payload in [("start", "match_start", {}),
        ("score", "score", {"team_id": "club-alpha-1", "event_type": event})]:
        action = {"action_id": eid, "type": "score_event", "queued_at": "2026-10-18T10:00:00Z",
            "base_version": 0, "payload": {"event_id": eid, "match_id": match, "type": kind, "payload": payload}}
        response = client.post("/api/v1/offline-sync", json={"device_id": "phone", "actions": [action]}, headers=headers)
        assert response.status_code == 200, response.text
        assert response.json()["results"][0]["status"] == "applied"
        duplicate = client.post("/api/v1/offline-sync", json={"device_id": "phone", "actions": [action]}, headers=headers)
        assert duplicate.json()["results"][0]["status"] == "duplicate"
    assert client.get(f"/api/v1/matches/{match}", headers=headers).json()["home"]["score"] == points


def test_management_role_and_tenant_boundaries(client, login):
    viewer = login("viewer")
    assert client.post("/api/v1/leagues", json=league_body(), headers=viewer).status_code == 403
    assert client.post("/api/v1/matches", json=match_body(), headers=viewer).status_code == 403
    assert client.get("/api/v1/management/products", headers=viewer).status_code == 403
    organizer = login("organizer")
    assert client.post("/api/v1/matches", json=match_body(), headers=organizer).status_code == 201
    assert client.post("/api/v1/matches", json=match_body("league-alpha-2"), headers=organizer).status_code == 403
    assert client.get("/api/v1/leagues/league-beta-1/management", headers=organizer).status_code == 403
    clubs = client.get("/api/v1/clubs", headers=viewer).json()
    assert clubs and all(c["tenant_id"] == "tenant-alpha" for c in clubs)
    cross = league_body() | {"teams": ["club-alpha-1", "club-beta-1"]}
    assert client.post("/api/v1/leagues", json=cross, headers=login("admin")).status_code == 400


def test_match_validation_and_collision_are_atomic(client, login):
    headers = login("admin")
    body = match_body()
    assert client.post("/api/v1/matches", json=body | {"away_team_id": "club-alpha-1"}, headers=headers).status_code == 400
    assert client.post("/api/v1/matches", json=body | {"scheduled_at": "invalid"}, headers=headers).status_code == 400
    assert client.post("/api/v1/matches", json=body, headers=headers).status_code == 201
    assert client.post("/api/v1/matches", json=body, headers=headers).status_code == 409
    assert client.post("/api/v1/matches", json=body | {"scheduled_at": "2026-10-19T10:30:00Z"}, headers=headers).status_code == 409
    assert len(client.get("/api/v1/fixtures", headers=headers).json()) == 7


def product_body():
    return {"name": "Away kit", "club_id": "club-alpha-1", "category": "apparel",
        "variants": [{"label": "M / Green", "stock": 3, "price_minor": 120000}]}


def test_manage_product_stock_prices_and_permissions(client, login):
    headers = login("manager")
    response = client.post("/api/v1/products", json=product_body(), headers=headers)
    assert response.status_code == 201, response.text
    product = response.json()
    variant = product["variants"][0]["variant_id"]
    url = f'/api/v1/products/{product["product_id"]}/variants/{variant}'
    assert client.patch(url, json={"stock": 9, "price_minor": 10000}, headers=headers).json()["stock"] == 9
    assert client.patch(url, json={"stock": -1}, headers=headers).status_code == 400
    assert client.patch(url, json={"stock": 2}, headers=login("viewer")).status_code == 403
    assert client.post("/api/v1/products", json=product_body() | {"club_id": "club-alpha-2"}, headers=headers).status_code == 403
    assert all(p["club_id"] == "club-alpha-1" for p in client.get("/api/v1/management/products", headers=headers).json())
    assert client.get("/api/v1/management/products").status_code == 401
    assert any(p["name"] == "Away kit" for p in client.get("/api/v1/products", headers=login("viewer")).json())


def test_authenticated_sync_rejects_wrong_role(client, login):
    action = {"action_id": "offline-start", "type": "score_event", "queued_at": "2026-10-18T10:00:00Z",
        "base_version": 0, "payload": {"event_id": "offline-start", "match_id": "demo-m6", "type": "match_start", "payload": {}}}
    body = {"device_id": "phone", "actions": [action]}
    assert client.post("/api/v1/offline-sync", json=body).status_code == 401
    assert client.post("/api/v1/offline-sync", json=body, headers=login("viewer")).status_code == 403
    assert client.post("/api/v1/offline-sync", json=body, headers=login("organizer")).json()["results"][0]["status"] == "applied"
