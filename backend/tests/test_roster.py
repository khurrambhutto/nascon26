from copy import deepcopy
import pytest


def scenario():
    return {"league": {"league_id": "roster-test", "tenant_id": "tenant-alpha", "sport_id": "football", "transfer_window": {"open": "2026-09-01", "close": "2026-09-30"}, "max_squad_size": 3, "required_positions": {"gk": 1}}, "squads": [{"squad_id": "sa", "club_id": "ca", "tenant_id": "tenant-alpha", "name": "A"}, {"squad_id": "sb", "club_id": "cb", "tenant_id": "tenant-alpha", "name": "B"}], "players": [{"player_id": "p1", "name": "Ali", "position": "gk", "squad_id": "sa", "role": "captain"}, {"player_id": "p2", "name": "Bilal", "position": "df", "squad_id": "sa", "role": "starter"}], "matches_in_progress": []}


def move(**updates):
    return {"request_id": "tr-1", "league_id": "roster-test", "player_id": "p2", "type": "transfer", "from_squad_id": "sa", "to_squad_id": "sb", "effective_date": "2026-09-30", "loan_end_date": None, "reason": "Tactical move", "requested_by": "u-manager", **updates}


def test_transfer_replay_and_history(client):
    assert client.post("/audit/v1/roster/setup", json=scenario()).status_code == 201
    response = client.post("/audit/v1/roster/transfer", json=move())
    assert response.status_code == 201, response.text
    assert response.json()["registration"]["role"] == "substitute"
    replay = client.post("/audit/v1/roster/transfer", json=move())
    assert replay.status_code == 200 and replay.json() == response.json()
    history = client.get("/audit/v1/roster/history/p2?as_of=2026-10-01").json()
    assert history["current_squad_id"] == "sb" and len(history["history"]) == 1
    assert client.get("/audit/v1/roster/history/p2?as_of=2026-09-10").json()["current_squad_id"] == "sa"


def test_loan_automatic_return_is_once_and_historical(client):
    client.post("/audit/v1/roster/setup", json=scenario())
    response = client.post("/audit/v1/roster/transfer", json=move(type="loan", loan_end_date="2026-10-15"))
    assert response.status_code == 201
    before = client.get("/audit/v1/roster/history/p2?as_of=2026-10-15").json()
    assert before["current_squad_id"] == "sb" and before["parent_squad_id"] == "sa"
    after = client.get("/audit/v1/roster/history/p2?as_of=2026-10-16").json()
    assert after["current_squad_id"] == "sa" and after["parent_squad_id"] is None
    assert after["history"][-1]["action"] == "loan_return"
    repeated = client.get("/audit/v1/roster/history/p2?as_of=2026-10-16").json()
    assert repeated == after
    assert client.get("/audit/v1/roster/history/p2?as_of=2026-10-01").json()["current_squad_id"] == "sb"


def test_active_loan_blocks_all_changes(client):
    client.post("/audit/v1/roster/setup", json=scenario())
    assert client.post("/audit/v1/roster/transfer", json=move(type="loan", loan_end_date="2026-11-01", effective_date="2026-09-01")).status_code == 201
    for kind in ("transfer", "loan", "release"):
        body = move(request_id="blocked-"+kind, type=kind, from_squad_id="sb", to_squad_id="sa" if kind != "release" else None, effective_date="2026-09-30", loan_end_date="2026-11-02" if kind == "loan" else None)
        response = client.post("/audit/v1/roster/transfer", json=body)
        assert response.status_code == 409 and response.json()["error"]["code"] == "PLAYER_ON_LOAN"


def test_last_window_day_deferred_transfer(client):
    body = scenario()
    body["matches_in_progress"] = [{"match_id": "m-live", "home_squad_id": "sa", "away_squad_id": "sb"}]
    client.post("/audit/v1/roster/setup", json=body)
    response = client.post("/audit/v1/roster/transfer", json=move())
    assert response.status_code == 201
    assert response.json()["state"] == "deferred_until_match_end"
    assert response.json()["blocking_match_id"] == "m-live"
    assert client.get("/audit/v1/roster/history/p2?as_of=2026-10-01").json()["current_squad_id"] == "sa"


def test_release_outside_window(client):
    client.post("/audit/v1/roster/setup", json=scenario())
    response = client.post("/audit/v1/roster/transfer", json=move(type="release", to_squad_id=None, effective_date="2027-01-01"))
    assert response.status_code == 201 and response.json()["registration"] is None
    assert client.get("/audit/v1/roster/history/p2?as_of=2027-01-02").json()["current_squad_id"] is None


@pytest.mark.parametrize("updates,status,code", [({"loan_end_date": "2026-09-01", "type": "loan"}, 400, "VALIDATION_ERROR"), ({"league_id": "missing"}, 404, "LEAGUE_NOT_FOUND"), ({"player_id": "missing"}, 404, "PLAYER_NOT_FOUND"), ({"to_squad_id": "missing"}, 404, "SQUAD_NOT_FOUND"), ({"from_squad_id": "sb", "to_squad_id": "sa"}, 409, "NOT_IN_SQUAD"), ({"effective_date": "2026-10-01"}, 409, "TRANSFER_WINDOW_CLOSED"), ({"player_id": "p1"}, 409, "MIN_POSITION_VIOLATION")])
def test_roster_error_order(client, updates, status, code):
    client.post("/audit/v1/roster/setup", json=scenario())
    response = client.post("/audit/v1/roster/transfer", json=move(**updates))
    assert response.status_code == status, response.text
    assert response.json()["error"]["code"] == code
    assert len(client.get("/audit/v1/roster/history/p2?as_of=2026-10-01").json()["history"]) == 0


def test_squad_capacity_and_cross_tenant(client):
    body = scenario()
    body["league"]["max_squad_size"] = 1
    body["players"].append({"player_id": "p3", "name": "Zain", "position": "df", "squad_id": "sb", "role": "starter"})
    client.post("/audit/v1/roster/setup", json=body)
    assert client.post("/audit/v1/roster/transfer", json=move()).json()["error"]["code"] == "SQUAD_FULL"
    body["squads"][1]["tenant_id"] = "tenant-beta"
    client.post("/audit/v1/roster/setup", json=body)
    assert client.post("/audit/v1/roster/transfer", json=move()).json()["error"]["code"] == "CROSS_TENANT_TRANSFER"


def test_scenario_defaults_replacement_and_unknown_player(client):
    body = scenario()
    del body["league"]["max_squad_size"]
    del body["league"]["required_positions"]
    assert client.post("/audit/v1/roster/setup", json=body).status_code == 201
    assert client.post("/audit/v1/roster/transfer", json=move()).status_code == 201
    assert client.post("/audit/v1/roster/setup", json=body).status_code == 201
    assert client.get("/audit/v1/roster/history/p2?as_of=2026-10-01").json()["current_squad_id"] == "sa"
    assert client.get("/audit/v1/roster/history/missing").status_code == 404
