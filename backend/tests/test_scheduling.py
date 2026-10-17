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
