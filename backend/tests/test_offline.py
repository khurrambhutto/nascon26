import pytest


def action(aid, eid, queued="2026-10-17T11:00:00Z", version=2, **updates):
    return {"action_id": aid, "type": "score_event", "queued_at": queued, "base_version": version, "payload": {"event_id": eid, "match_id": "demo-m3", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}}, **updates}


def test_chronological_processing_and_request_order_results(client):
    actions = [action("later", "later", "2026-10-17T11:01:00Z"), action("first", "first")]
    response = client.post("/audit/v1/offline-sync", json={"device_id": "dev", "actions": actions}).json()
    assert [r["processed_order"] for r in response["results"]] == [2, 1]
    assert [r["server_version"] for r in response["results"]] == [4, 3]
    assert [r["resolution"] for r in response["results"]] == ["rebased_append", "appended"]
    assert response["summary"] == {"applied": 2, "rejected": 0, "duplicate": 0}


def test_rejections_duplicates_and_versions(client):
    actions = [action("future", "future", version=99), action("unknown", "unknown", payload={"event_id": "unknown", "match_id": "missing", "type": "match_start", "payload": {}}), action("valid", "valid"), action("same-event", "valid")]
    body = {"device_id": "dev", "actions": actions}
    response = client.post("/audit/v1/offline-sync", json=body).json()
    assert response["summary"] == {"applied": 1, "rejected": 2, "duplicate": 1}
    assert [r["error_code"] for r in response["results"]][:2] == ["INVALID_BASE_VERSION", "MATCH_NOT_FOUND"]
    assert client.post("/audit/v1/offline-sync", json=body).json()["summary"] == {"applied": 0, "rejected": 0, "duplicate": 4}


def test_rule_rejected_offline(client):
    bad = action("bad", "bad", payload={"event_id": "bad", "match_id": "demo-m1", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}}, version=0)
    result = client.post("/audit/v1/offline-sync", json={"device_id": "dev", "actions": [bad]}).json()["results"][0]
    assert result["status"] == "rejected" and result["error_code"] == "MATCH_NOT_IN_PROGRESS"


def test_malformed_action_payload_is_per_action_validation_error(client):
    body = {"device_id": "dev", "actions": [action("malformed", "unused", payload={})]}
    response = client.post("/audit/v1/offline-sync", json=body)
    assert response.status_code == 200
    assert response.json()["results"][0]["error_code"] == "VALIDATION_ERROR"


def test_offline_roster_actions_use_shared_rules(client):
    payload = {"request_id": "offline-transfer", "league_id": "league-alpha-1", "player_id": "demo-player-1-1", "type": "transfer", "from_squad_id": "demo-squad-1", "to_squad_id": "demo-squad-2", "effective_date": "2026-10-17", "reason": "Offline roster action", "requested_by": "u-manager"}
    body = {"device_id": "roster-device", "actions": [{"action_id": "roster-action", "type": "roster_transfer", "queued_at": "2026-10-17T11:00:00Z", "base_version": 0, "payload": payload}]}
    response = client.post("/audit/v1/offline-sync", json=body).json()
    assert response["results"][0] == {"action_id": "roster-action", "status": "applied", "resolution": "appended", "error_code": None, "processed_order": 1, "server_version": 1, "signature": None}
    assert client.post("/audit/v1/offline-sync", json=body).json()["summary"]["duplicate"] == 1
    body["actions"][0]["action_id"] = "rejected-action"
    body["actions"][0]["payload"]["request_id"] = "another-transfer"
    response = client.post("/audit/v1/offline-sync", json=body).json()
    assert response["results"][0]["error_code"] == "NOT_IN_SQUAD"
    assert response["results"][0]["server_version"] == 1


@pytest.mark.parametrize("updates", [{"actions": []}, {"device_id": "bad|id"}, {"actions": [action("a", "a", queued="bad")]}, {"actions": [action("a", "a", version=True)]}, {"actions": [action("a", "a", type="unknown")]}])
def test_invalid_offline_envelope(client, updates):
    response = client.post("/audit/v1/offline-sync", json={"device_id": "dev", "actions": [action("a", "a")], **updates})
    assert response.status_code == 400
