"""Sport rules are data. Scores are projections of immutable signed events."""
import re
from copy import deepcopy

from .common import TIE_BREAKS, check, fail, identifier, integer, obj, sign, text, tie_breaks


def sport_config(body):
    body = obj(body)
    sport_id = body.get("sport_id")
    check(isinstance(sport_id, str) and re.fullmatch(r"[a-z0-9_-]{2,40}", sport_id) is not None, "sport_id")
    text(body.get("name"), "name", 80)
    model = body.get("scoring_model")
    check(model in ("event", "innings", "round"), "scoring_model")
    periods = obj(body.get("periods"), "periods")
    fields = {"event": ["count", "duration_minutes"], "innings": ["innings_per_team", "overs_per_innings", "balls_per_over", "wickets_per_innings"], "round": ["best_of"]}[model]
    for key in fields:
        integer(periods.get(key), "periods." + key, 1)
    if model == "round":
        check(periods["best_of"] % 2 == 1, "periods.best_of")
    rules = obj(body.get("scoring_events"), "scoring_events")
    check(bool(rules), "scoring_events")
    for name, rule in rules.items():
        check(re.fullmatch(r"[a-z0-9_]{1,40}", name) is not None, "scoring_events")
        obj(rule, "scoring_events." + name)
        if model == "innings":
            integer(rule.get("runs"), "runs", 0)
            check(type(rule.get("legal_ball")) is bool and type(rule.get("wicket")) is bool, "legal_ball", "wicket")
        else:
            key = "points" if model == "event" else "rounds"
            integer(rule.get(key), key, 1)
    check(type(body.get("draw_allowed")) is bool, "draw_allowed")
    overtime = obj(body.get("overtime"), "overtime")
    check(overtime.get("policy") in ("none", "extra_time", "shootout", "golden_score", "super_over"), "overtime.policy")
    if "duration_minutes" in overtime:
        integer(overtime["duration_minutes"], "overtime.duration_minutes", 0)
    roster = obj(body.get("roster"), "roster")
    integer(roster.get("min_squad_size"), "roster.min_squad_size", 1)
    integer(roster.get("max_squad_size"), "roster.max_squad_size", roster["min_squad_size"])
    for name, count in obj(roster.get("required_positions"), "roster.required_positions").items():
        text(name, "position", 40)
        integer(count, "required_positions", 0)
    return {key: deepcopy(body[key]) for key in ("sport_id", "name", "scoring_model", "periods", "scoring_events", "draw_allowed", "overtime", "roster")} | {"tie_break_order": tie_breaks(body.get("tie_break_order", TIE_BREAKS))}


def create_sport(store, body):
    config = sport_config(body)
    if store.get("sport", config["sport_id"]):
        fail(409, "SPORT_EXISTS")
    store.put("sport", config["sport_id"], config)
    return {"sport_id": config["sport_id"], "status": "created", "config": config}


def register_league(store, body):
    obj(body)
    league_id = identifier(body.get("league_id"), "league_id")
    sport_id = identifier(body.get("sport_id"), "sport_id")
    teams = body.get("teams")
    check(isinstance(teams, list) and 2 <= len(teams) <= 40, "teams")
    for team in teams:
        identifier(team, "teams")
    check(len(set(teams)) == len(teams), "teams")
    fixtures = body.get("fixtures")
    check(isinstance(fixtures, list), "fixtures")
    seen = set()
    for fixture in fixtures:
        obj(fixture, "fixtures")
        mid = identifier(fixture.get("match_id"), "match_id")
        check(mid not in seen, "match_id")
        seen.add(mid)
        check(fixture.get("home") in teams and fixture.get("away") in teams and fixture["home"] != fixture["away"], "fixtures")
    points = obj(body.get("points", {"win": 3, "draw": 1, "loss": 0}), "points")
    for key in ("win", "draw", "loss"):
        integer(points.get(key), "points." + key)
    spots = integer(obj(body.get("qualification"), "qualification").get("spots"), "qualification.spots", 1, len(teams))
    lots = integer(body.get("lots_seed", store.get("meta", "seed", 42)), "lots_seed")
    if "tie_break_order" in body:
        tie_breaks(body["tie_break_order"])
    sport = store.require("sport", sport_id)
    if store.get("league", league_id):
        fail(409, "LEAGUE_EXISTS")
    for fixture in fixtures:
        if store.get("match", fixture["match_id"]):
            fail(409, "MATCH_EXISTS")
    league = {"league_id": league_id, "sport_id": sport_id, "teams": teams, "fixtures": fixtures, "points": points, "qualification": {"spots": spots}, "lots_seed": lots, "tie_break_order": tie_breaks(body.get("tie_break_order", sport["tie_break_order"])), "tenant_id": body.get("tenant_id", "tenant-alpha"), "name": body.get("name", league_id)}
    store.put("league", league_id, league)
    for fixture in fixtures:
        store.put("match", fixture["match_id"], {"match_id": fixture["match_id"], "sport_id": sport_id, "league_id": league_id, "home_team_id": fixture["home"], "away_team_id": fixture["away"], "tenant_id": league["tenant_id"], "events": [], "scheduled_at": fixture.get("scheduled_at"), "venue": fixture.get("venue"), "pinned_product_id": None})
    return {"league_id": league_id, "status": "created", "team_count": len(teams), "fixture_count": len(fixtures)}


# Day 2: operational states that do not come from the scoring timeline.
STATUS_EVENTS = {"match_postponed": "postponed", "match_cancelled": "cancelled", "match_abandoned": "abandoned", "status_change": None}
OPERATIONAL_MOVES = {"upcoming": ("postponed", "cancelled"), "in_progress": ("postponed", "abandoned"), "postponed": ("upcoming", "in_progress", "cancelled")}


def effective_status(match, timeline_status):
    """A finished timeline always wins; otherwise an operational override (postponed etc.) applies."""
    if timeline_status == "completed":
        return "completed"
    return match.get("operational_status") or timeline_status


def project(store, match_id):
    match = store.require("match", match_id)
    sport = store.require("sport", match["sport_id"])
    model, periods = sport["scoring_model"], sport["periods"]
    targets = {event["payload"]["compensates"]: event["event_id"] for event in match["events"] if event["type"] == "compensation"}
    sides = {}
    for side in ("home", "away"):
        sides[side] = {"team_id": match[side + "_team_id"], "score": 0, "wickets": 0 if model == "innings" else None, "balls": 0 if model == "innings" else None, "overs": "0.0" if model == "innings" else None, "fair_play": 0}
    state, tiebreak = "upcoming", None
    events = []
    for raw in match["events"]:
        event = {key: deepcopy(raw[key]) for key in ("sequence", "event_id", "type", "payload", "signature")}
        event.update(compensated=raw["event_id"] in targets, compensated_by=targets.get(raw["event_id"]))
        events.append(event)
        kind, payload = raw["type"], raw["payload"]
        if kind == "match_start":
            state = "in_progress"
        elif kind == "match_end":
            state = "completed"
        if event["compensated"] or kind == "compensation":
            continue
        if kind in ("score", "discipline", "tiebreak_winner"):
            side = sides["home"] if payload["team_id"] == match["home_team_id"] else sides["away"]
            if kind == "discipline":
                side["fair_play"] += payload["points"]
            elif kind == "tiebreak_winner":
                tiebreak = payload["team_id"]
            else:
                rule = sport["scoring_events"][payload["event_type"]]
                side["score"] += rule[{"event": "points", "innings": "runs", "round": "rounds"}[model]]
                if model == "innings":
                    side["wickets"] += int(rule["wicket"])
                    side["balls"] += int(rule["legal_ball"])
    if model == "innings":
        for side in sides.values():
            side["overs"] = f"{side['balls'] // periods['balls_per_over']}.{side['balls'] % periods['balls_per_over']}"
    h, a = sides["home"], sides["away"]
    winner = h["team_id"] if h["score"] > a["score"] else a["team_id"] if a["score"] > h["score"] else tiebreak
    outcome = "pending"
    if state == "completed":
        outcome = "home_win" if winner == h["team_id"] else "away_win" if winner == a["team_id"] else "draw" if sport["draw_allowed"] else "undecided"
    return {"match_id": match_id, "sport_id": match["sport_id"], "league_id": match.get("league_id"), "status": state, "outcome": outcome, "winner_team_id": winner, "tiebreak_winner_team_id": tiebreak, **sides, "version": len(events), "events": events}


def validate_event(body):
    obj(body)
    eid = identifier(body.get("event_id"), "event_id")
    mid = identifier(body.get("match_id"), "match_id")
    kind = body.get("type")
    check(kind in ("match_init", "match_start", "score", "discipline", "tiebreak_winner", "match_end", "compensation") or kind in STATUS_EVENTS, "type")
    payload = obj(body.get("payload"), "payload")
    if kind == "match_init":
        for key in ("sport_id", "home_team_id", "away_team_id"):
            identifier(payload.get(key), "payload." + key)
        check(payload["home_team_id"] != payload["away_team_id"], "away_team_id")
        if payload.get("league_id") is not None:
            identifier(payload["league_id"], "league_id")
    elif kind in ("score", "discipline", "tiebreak_winner"):
        identifier(payload.get("team_id"), "team_id")
        if kind == "score":
            text(payload.get("event_type"), "event_type", 40)
        if kind == "discipline":
            integer(payload.get("points"), "points")
    elif kind == "compensation":
        identifier(payload.get("compensates"), "compensates")
        text(payload.get("reason"), "reason")
    return {"event_id": eid, "match_id": mid, "type": kind, "payload": deepcopy(payload)}


def append_event(store, body):
    body = validate_event(body)
    eid, mid, kind, payload = body["event_id"], body["match_id"], body["type"], body["payload"]
    previous = store.get("event", eid)
    if previous:
        if previous["request"] != body:
            fail(409, "DUPLICATE_EVENT_ID")
        return previous["response"], False
    match = store.get("match", mid)
    if kind == "match_init":
        sport = store.require("sport", payload["sport_id"])
        if payload.get("league_id"):
            store.require("league", payload["league_id"])
        if match:
            fail(409, "MATCH_EXISTS")
        match = {"match_id": mid, "sport_id": sport["sport_id"], "home_team_id": payload["home_team_id"], "away_team_id": payload["away_team_id"], "league_id": payload.get("league_id"), "tenant_id": "tenant-alpha", "events": []}
    else:
        match = store.require("match", mid)
        sport = store.require("sport", match["sport_id"])
        projection = project(store, mid)
        if kind == "compensation":
            target = store.require("event", payload["compensates"], "EVENT_NOT_FOUND")
            request = target["request"]
            check_target = request["match_id"] == mid and request["type"] in ("score", "discipline", "tiebreak_winner")
            if not check_target or any(e["type"] == "compensation" and e["payload"]["compensates"] == payload["compensates"] for e in match["events"]):
                fail(409, "INVALID_COMPENSATION")
        elif kind == "match_start":
            if projection["status"] != "upcoming":
                fail(409, "ILLEGAL_TRANSITION")
        elif kind in STATUS_EVENTS:
            target = payload.get("status") if kind == "status_change" else STATUS_EVENTS[kind]
            if target not in OPERATIONAL_MOVES.get(effective_status(match, projection["status"]), ()):
                fail(409, "ILLEGAL_TRANSITION")
            match["operational_status"] = target
        elif kind == "match_end":
            if projection["status"] != "in_progress":
                fail(409, "ILLEGAL_TRANSITION")
            if projection["home"]["score"] == projection["away"]["score"] and not sport["draw_allowed"] and not projection["tiebreak_winner_team_id"]:
                fail(409, "TIE_NOT_ALLOWED")
        else:
            check(payload["team_id"] in (match["home_team_id"], match["away_team_id"]), "team_id")
            if kind == "score" and payload["event_type"] not in sport["scoring_events"]:
                fail(400, "UNKNOWN_EVENT_TYPE")
            if projection["status"] != "in_progress":
                fail(409, "MATCH_NOT_IN_PROGRESS")
            if kind == "score":
                periods = sport["periods"]
                side = projection["home"] if payload["team_id"] == match["home_team_id"] else projection["away"]
                if sport["scoring_model"] == "innings" and (side["balls"] >= periods["innings_per_team"] * periods["overs_per_innings"] * periods["balls_per_over"] or side["wickets"] >= periods["innings_per_team"] * periods["wickets_per_innings"]):
                    fail(409, "INNINGS_COMPLETE")
                if sport["scoring_model"] == "round" and max(projection["home"]["score"], projection["away"]["score"]) >= (periods["best_of"] + 1) // 2:
                    fail(409, "SERIES_ALREADY_DECIDED")
    sequence = len(match["events"]) + 1
    signature = sign(eid, payload)
    response = {"event_id": eid, "match_id": mid, "type": kind, "sequence": sequence, "match_version": sequence, "signature": signature}
    match["events"].append({**body, "sequence": sequence, "signature": signature})
    store.put("match", mid, match)
    store.put("event", eid, {"request": body, "response": response})
    return response, True
