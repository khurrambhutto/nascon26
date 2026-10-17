def test_venue_overlap_and_status_flow(client, login):
    headers = login("organizer")
    venue = {"name": "Main Ground", "availability": [{"start": "2026-10-17T00:00:00Z", "end": "2026-10-31T23:59:59Z"}]}
    assert client.put("/api/v1/venues/main", headers=headers, json=venue).status_code == 200
    body = {"scheduled_at": "2026-10-25T10:00:00Z", "duration_minutes": 90, "venue_id": "main"}
    assert client.post("/api/v1/matches/demo-m4/reschedule", headers=headers, json=body).status_code == 200
    response = client.post("/api/v1/matches/demo-m5/reschedule", headers=headers, json=body)
    assert response.status_code == 409
    assert {c["type"] for c in response.json()["error"]["details"]["conflicts"]} >= {"team_overlap", "venue_double_booking"}
    assert client.post("/api/v1/matches/demo-m4/status", headers=headers, json={"status": "postponed"}).status_code == 200
    assert client.get("/api/v1/matches/demo-m4", headers=headers).json()["status"] == "postponed"
    assert client.post("/api/v1/matches/demo-m4/status", headers=headers, json={"status": "completed"}).status_code == 409
    assert client.post("/api/v1/matches/demo-m4/status", headers=headers, json={"status": "upcoming"}).status_code == 200
    assert client.post("/api/v1/matches/demo-m4/status", headers=headers, json={"status": "in_progress"}).status_code == 200
    assert client.post("/api/v1/matches/demo-m4/status", headers=headers, json={"status": "completed"}).status_code == 200


def test_scheduling_permissions_and_availability(client, login):
    viewer = login("viewer")
    assert client.put("/api/v1/venues/field", headers=viewer, json={"name": "Field"}).status_code == 403
    organizer = login("organizer")
    assert client.put("/api/v1/venues/field", headers=organizer, json={"name": "Field", "availability": [{"start": "2026-10-17T08:00:00Z", "end": "2026-10-17T09:00:00Z"}]}).status_code == 200
    response = client.post("/api/v1/matches/demo-m4/reschedule", headers=organizer, json={"scheduled_at": "2026-10-18T08:00:00Z", "venue_id": "field"})
    assert response.status_code == 409
    assert response.json()["error"]["details"]["conflicts"][0]["type"] == "venue_unavailable"


def test_league_configuration_and_minimum_rest(client, login):
    headers = login("organizer")
    config = {"minimum_rest_minutes": 60, "season": "2026", "format": "hybrid", "divisions": [{"division_id": "division-a", "name": "Division A", "teams": ["club-alpha-1", "club-alpha-2"]}], "promotion_relegation": {"promote": 1, "relegate": 1}}
    assert client.patch("/api/v1/leagues/league-alpha-1/configuration", headers=headers, json=config).status_code == 200
    assert client.patch("/api/v1/leagues/league-alpha-2/configuration", headers=headers, json=config).status_code == 403
    assert client.put("/api/v1/venues/field", headers=headers, json={"name": "Field"}).status_code == 200
    body = {"scheduled_at": "2026-10-29T10:00:00Z", "duration_minutes": 90, "venue_id": "field"}
    assert client.post("/api/v1/matches/demo-m4/reschedule", headers=headers, json=body).status_code == 200
    response = client.post("/api/v1/matches/demo-m5/reschedule", headers=headers, json={**body, "scheduled_at": "2026-10-29T12:00:00Z"})
    assert response.status_code == 409
    assert any(c["type"] == "minimum_rest" for c in response.json()["error"]["details"]["conflicts"])


def test_branding_requires_platform_admin(client, login):
    path = "/api/v1/tenants/tenant-alpha/branding"
    assert client.patch(path, headers=login("manager"), json={"primary_color": "#112233"}).status_code == 403
    admin = login("admin")
    assert client.patch(path, headers=admin, json={"primary_color": "red"}).status_code == 400
    assert client.patch(path, headers=admin, json={"name": "Campus League", "primary_color": "#112233", "logo_url": None}).status_code == 200
    assert client.get("/api/v1/me", headers=admin).json()["tenant"]["name"] == "Campus League"
