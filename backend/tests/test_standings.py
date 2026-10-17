from itertools import combinations

import pytest


def league(client, sport="football", order=None, points=None, teams=None):
    teams = teams or ["a", "b", "c", "d"]
    fixtures = [{"match_id": "s-"+a+"-"+b, "home": a, "away": b} for a, b in combinations(teams, 2)]
    body = {"league_id": "standings-test", "sport_id": sport, "teams": teams, "fixtures": fixtures, "qualification": {"spots": 2}, "lots_seed": 42}
    if order is not None:
        body["tie_break_order"] = order
    if points is not None:
        body["points"] = points
    response = client.post("/audit/v1/leagues", json=body)
    assert response.status_code == 201, response.text
    return fixtures


def record(client, home, away, h, a, event_name="goal", end=True, discipline=None):
    mid = "s-"+home+"-"+away
    def emit(eid, type_, payload):
        response = client.post("/audit/v1/events", json={"event_id": mid+"-"+eid, "match_id": mid, "type": type_, "payload": payload})
        assert response.status_code == 201, response.text
    emit("start", "match_start", {})
    for team, count in ((home, h), (away, a)):
        for i in range(count):
            emit(team+str(i), "score", {"team_id": team, "event_type": event_name})
    if discipline is not None:
        emit("discipline", "discipline", {"team_id": home, "points": discipline})
    if end:
        emit("end", "match_end", {})


def test_three_way_h2h_mini_league(client):
    league(client)
    record(client, "a", "b", 2, 0)
    record(client, "a", "c", 0, 1)
    record(client, "b", "c", 3, 0)
    for team in "abc":
        record(client, team, "d", 1, 0)
    table = client.get("/audit/v1/standings/standings-test").json()
    assert [r["team_id"] for r in table["table"]] == ["a", "b", "c", "d"]
    assert [r["points"] for r in table["table"]] == [6, 6, 6, 0]
    assert table["h2h"]["a"]["b"] == {"played": 1, "points": 3, "scored": 2, "conceded": 0}
    assert table["table"][-1]["status"] == "eliminated"


def test_compensation_recomputes_completed_results(client):
    league(client, teams=["a", "b", "c"])
    record(client, "a", "b", 1, 0)
    before = client.get("/audit/v1/standings/standings-test").json()
    assert next(r for r in before["table"] if r["team_id"] == "a")["points"] == 3
    response = client.post("/audit/v1/events", json={"event_id": "undo-standings", "match_id": "s-a-b", "type": "compensation", "payload": {"compensates": "s-a-b-a0", "reason": "Disallowed goal"}})
    assert response.status_code == 201
    after = client.get("/audit/v1/standings/standings-test").json()
    assert next(r for r in after["table"] if r["team_id"] == "a")["points"] == 1
    assert after["h2h"]["a"]["b"]["scored"] == 0


@pytest.mark.parametrize("criterion", ["overall_diff", "scored", "fair_play", "draw_lots"])
def test_configured_tiebreaks_and_deterministic_lots(client, criterion):
    league(client, order=[criterion], teams=["a", "b", "c"])
    record(client, "a", "b", 1, 1, discipline=-2)
    record(client, "a", "c", 0, 0)
    record(client, "b", "c", 0, 0)
    value = client.get("/audit/v1/standings/standings-test").json()
    assert len(value["table"]) == 3
    assert value == client.get("/audit/v1/standings/standings-test").json()
    if criterion == "scored":
        assert value["table"][-1]["team_id"] == "c"
    if criterion == "fair_play":
        assert value["table"][-1]["team_id"] == "a"


def test_innings_differential_and_round_model(client):
    league(client, sport="cricket", teams=["a", "b", "c"])
    record(client, "a", "b", 2, 1, event_name="single")
    value = client.get("/audit/v1/standings/standings-test").json()
    assert value["table"][0]["diff"] == 0
    assert all(isinstance(r["diff"], (int, float)) for r in value["table"])


def test_in_progress_matches_do_not_affect_standings(client):
    league(client, teams=["a", "b", "c"])
    record(client, "a", "b", 5, 0, end=False)
    rows = client.get("/audit/v1/standings/standings-test").json()["table"]
    assert all(r["played"] == 0 and r["points"] == 0 for r in rows)
