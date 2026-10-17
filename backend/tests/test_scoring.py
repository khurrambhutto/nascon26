from copy import deepcopy
import pytest

from app.seed import sports


def event(client, eid, kind, payload=None, mid="m-test"):
    return client.post("/audit/v1/events", json={"event_id": eid, "match_id": mid, "type": kind, "payload": payload or {}})


def init(client, sport="football"):
    assert event(client, "e-init", "match_init", {"sport_id": sport, "home_team_id": "a", "away_team_id": "b"}).status_code == 201


@pytest.mark.parametrize("model", ["event", "innings", "round"])
def test_mystery_sport_configurable_models(client, model):
    original = next(s for s in sports() if s["scoring_model"] == model)
    config = deepcopy(original)
    config["sport_id"] = "mystery_" + model
    config["name"] = "A new sport"
    rule = {"points": 7} if model == "event" else {"runs": 8, "legal_ball": True, "wicket": False} if model == "innings" else {"rounds": 1}
    config["scoring_events"] = {"arbitrary_move": rule}
    response = client.post("/audit/v1/sports", json=config)
    assert response.status_code == 201, response.text
    assert client.post("/audit/v1/sports", json=config).status_code == 409
    init(client, config["sport_id"])
    assert event(client, "start", "match_start").status_code == 201
    assert event(client, "score", "score", {"team_id": "a", "event_type": "arbitrary_move"}).status_code == 201
    score = client.get("/audit/v1/score/m-test").json()
    assert score["home"]["score"] == (7 if model == "event" else 8 if model == "innings" else 1)
    if model == "innings":
        assert score["home"]["balls"] == 1 and score["home"]["overs"] == "0.1"
    assert event(client, "end", "match_end").status_code == 201


def test_tie_and_tiebreak_compensation(client):
    init(client, "basketball")
    assert event(client, "score-early", "score", {"team_id": "a", "event_type": "two_pointer"}).json()["error"]["code"] == "MATCH_NOT_IN_PROGRESS"
    assert event(client, "start", "match_start").status_code == 201
    assert event(client, "start-2", "match_start").json()["error"]["code"] == "ILLEGAL_TRANSITION"
    assert event(client, "end", "match_end").json()["error"]["code"] == "TIE_NOT_ALLOWED"
    assert event(client, "tb", "tiebreak_winner", {"team_id": "a"}).status_code == 201
    assert event(client, "discipline", "discipline", {"team_id": "a", "points": -3}).status_code == 201
    assert event(client, "end", "match_end").status_code == 201
    score = client.get("/audit/v1/score/m-test").json()
    assert score["outcome"] == "home_win" and score["home"]["fair_play"] == -3
    assert event(client, "undo", "compensation", {"compensates": "tb", "reason": "Wrong team"}).status_code == 201
    assert client.get("/audit/v1/score/m-test").json()["outcome"] == "undecided"
    assert event(client, "undo2", "compensation", {"compensates": "tb", "reason": "Again"}).status_code == 409
    assert event(client, "undo3", "compensation", {"compensates": "start", "reason": "Invalid target"}).status_code == 409
    assert event(client, "undo4", "compensation", {"compensates": "missing", "reason": "Missing target"}).status_code == 404


def test_innings_limits_and_rollback(client):
    config = deepcopy(sports()[2])
    config["sport_id"] = "short_game"
    config["periods"] = {"innings_per_team": 1, "overs_per_innings": 1, "balls_per_over": 2, "wickets_per_innings": 1}
    assert client.post("/audit/v1/sports", json=config).status_code == 201
    init(client, "short_game")
    event(client, "start", "match_start")
    event(client, "wide", "score", {"team_id": "a", "event_type": "wide"})
    event(client, "run", "score", {"team_id": "a", "event_type": "single"})
    event(client, "out", "score", {"team_id": "a", "event_type": "wicket"})
    assert event(client, "blocked", "score", {"team_id": "a", "event_type": "six"}).json()["error"]["code"] == "INNINGS_COMPLETE"
    event(client, "undo-out", "compensation", {"compensates": "out", "reason": "Not out"})
    assert event(client, "blocked", "score", {"team_id": "a", "event_type": "six"}).status_code == 201
    assert client.get("/audit/v1/score/m-test").json()["home"] == {"team_id": "a", "score": 8, "balls": 2, "wickets": 0, "overs": "1.0", "fair_play": 0}


def test_round_series_limit(client):
    init(client, "esports")
    event(client, "start", "match_start")
    for eid in ("r1", "r2"):
        assert event(client, eid, "score", {"team_id": "a", "event_type": "round_win"}).status_code == 201
    assert event(client, "r3", "score", {"team_id": "b", "event_type": "round_win"}).json()["error"]["code"] == "SERIES_ALREADY_DECIDED"


@pytest.mark.parametrize("updates", [{"sport_id": "Bad ID"}, {"name": ""}, {"periods": {"count": 0, "duration_minutes": 1}}, {"scoring_events": {}}, {"scoring_events": {"bad name": {"points": 1}}}, {"draw_allowed": 1}, {"overtime": {"policy": "invalid"}}, {"roster": {"min_squad_size": 5, "max_squad_size": 2, "required_positions": {}}}, {"tie_break_order": ["scored", "scored"]}])
def test_sport_validation(client, updates):
    body = {**deepcopy(sports()[0]), "sport_id": "new-sport", **updates}
    response = client.post("/audit/v1/sports", json=body)
    assert response.status_code == 400, response.text
    assert response.json()["error"]["details"]["fields"]


def test_event_errors_and_unknown_fields(client):
    assert event(client, "e", "match_start").status_code == 404
    assert event(client, "e", "match_init", {"sport_id": "missing", "home_team_id": "a", "away_team_id": "b"}).status_code == 404
    init(client)
    assert event(client, "another-init", "match_init", {"sport_id": "football", "home_team_id": "a", "away_team_id": "b"}).status_code == 409
    event(client, "start", "match_start")
    assert event(client, "bad-team", "score", {"team_id": "x", "event_type": "goal"}).status_code == 400
    assert event(client, "bad-event", "score", {"team_id": "a", "event_type": "mystery"}).json()["error"]["code"] == "UNKNOWN_EVENT_TYPE"
    event(client, "end", "match_end")
    assert event(client, "end-2", "match_end").status_code == 409
    assert client.get("/audit/v1/score/missing").status_code == 404
    assert client.get("/audit/v1/standings/missing").status_code == 404


def test_league_validation(client):
    body = {"league_id": "test-league", "sport_id": "football", "teams": ["a", "b"], "fixtures": [{"match_id": "test-match", "home": "a", "away": "b"}], "qualification": {"spots": 1}}
    assert client.post("/audit/v1/leagues", json=body).status_code == 201
    assert client.post("/audit/v1/leagues", json=body).json()["error"]["code"] == "LEAGUE_EXISTS"
    assert client.post("/audit/v1/leagues", json={**body, "league_id": "another"}).json()["error"]["code"] == "MATCH_EXISTS"
    assert client.post("/audit/v1/leagues", json={**body, "league_id": "other", "sport_id": "unknown"}).status_code == 404
    assert client.post("/audit/v1/leagues", json={**body, "teams": ["a", "a"]}).status_code == 400


def test_non_finite_json_payload_is_rejected_without_mutation(client):
    raw = '{"event_id":"nan-event","match_id":"demo-m3","type":"score","payload":{"team_id":"club-alpha-1","event_type":"goal","unknown":NaN}}'
    response = client.post("/audit/v1/events", content=raw, headers={"Content-Type": "application/json"})
    assert response.status_code == 400
    assert client.get("/audit/v1/score/demo-m3").json()["version"] == 2
