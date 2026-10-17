"""Roster rule checks, append-only history, deferred moves and automatic loans."""
from copy import deepcopy
from datetime import date, timedelta

from .common import check, day, fail, identifier, integer, now, obj, text


def setup(store, body):
    obj(body)
    league = obj(body.get("league"), "league")
    lid = identifier(league.get("league_id"), "league_id")
    for key in ("tenant_id", "sport_id"):
        identifier(league.get(key), key)
    window = obj(league.get("transfer_window"), "transfer_window")
    check(day(window.get("open"), "open") <= day(window.get("close"), "close"), "transfer_window")
    squads, players = body.get("squads"), body.get("players")
    check(isinstance(squads, list) and isinstance(players, list), "squads", "players")
    squad_ids, player_ids = set(), set()
    for squad in squads:
        obj(squad, "squads")
        for key in ("squad_id", "club_id", "tenant_id"):
            identifier(squad.get(key), key)
        text(squad.get("name"), "name", 80)
        check(squad["squad_id"] not in squad_ids, "squad_id")
        squad_ids.add(squad["squad_id"])
    for player in players:
        obj(player, "players")
        pid = identifier(player.get("player_id"), "player_id")
        text(player.get("name"), "name", 80)
        text(player.get("position"), "position", 40)
        check(player.get("role") in ("captain", "vice_captain", "starter", "substitute"), "role")
        check(player.get("squad_id") in squad_ids and pid not in player_ids, "squad_id", "player_id")
        player_ids.add(pid)
    live = body.get("matches_in_progress", [])
    check(isinstance(live, list), "matches_in_progress")
    for match in live:
        obj(match)
        identifier(match.get("match_id"), "match_id")
        check(match.get("home_squad_id") in squad_ids and match.get("away_squad_id") in squad_ids, "matches_in_progress")
    if "max_squad_size" in league:
        integer(league["max_squad_size"], "max_squad_size", 1)
    if "required_positions" in league:
        for count in obj(league["required_positions"], "required_positions").values():
            integer(count, "required_positions", 0)
    sport = store.require("sport", league["sport_id"])
    config = deepcopy(league)
    config.setdefault("max_squad_size", sport["roster"]["max_squad_size"])
    config.setdefault("required_positions", sport["roster"]["required_positions"])
    config["matches_in_progress"] = live
    # Replacing a scenario removes only this league's roster records.
    for kind, key in (("player", "player_id"), ("squad", "squad_id"), ("transfer", "request_id")):
        for entry in store.all(kind):
            if entry.get("league_id") == lid:
                store.remove(kind, entry[key])
    store.put("roster_league", lid, config)
    for squad in squads:
        store.put("squad", squad["squad_id"], {**squad, "league_id": lid})
    for player in players:
        registration = {"player_id": player["player_id"], "league_id": lid, "squad_id": player["squad_id"], "parent_squad_id": None, "registration_type": "initial", "start_date": window["open"], "loan_end_date": None, "role": player["role"]}
        store.put("player", player["player_id"], {**player, "league_id": lid, "registration": registration, "initial_registration": deepcopy(registration), "history": [], "pending": None})
    return {"league_id": lid, "status": "configured", "squad_counts": {sid: sum(p["squad_id"] == sid for p in players) for sid in sorted(squad_ids)}}


def validate_transfer(body):
    obj(body)
    keys = ("request_id", "league_id", "player_id", "from_squad_id")
    for key in keys:
        identifier(body.get(key), key)
    kind = body.get("type")
    check(kind in ("transfer", "loan", "release"), "type")
    if kind != "release":
        identifier(body.get("to_squad_id"), "to_squad_id")
        check(body["to_squad_id"] != body["from_squad_id"], "to_squad_id")
    else:
        check(body.get("to_squad_id") is None, "to_squad_id")
    effective = day(body.get("effective_date"), "effective_date")
    if kind == "loan":
        check(day(body.get("loan_end_date"), "loan_end_date") > effective, "loan_end_date")
    elif body.get("loan_end_date") is not None:
        day(body["loan_end_date"], "loan_end_date")
    text(body.get("reason"), "reason")
    identifier(body.get("requested_by"), "requested_by")
    return {key: deepcopy(body.get(key)) for key in (*keys, "type", "to_squad_id", "effective_date", "loan_end_date", "reason", "requested_by")}


def live_matches(store, league):
    result = []
    for match in league.get("matches_in_progress", []):
        actual = store.get("match", match["match_id"])
        if actual:
            from .scoring import project
            if project(store, match["match_id"])["status"] == "completed":
                continue
        result.append(match)
    return result


def materialize(store, player, as_of=None):
    as_of = as_of or date.today()
    league = store.require("roster_league", player["league_id"], "LEAGUE_NOT_FOUND")
    pending = player.get("pending")
    if pending and not any(m["match_id"] == pending["blocking_match_id"] for m in live_matches(store, league)):
        player["registration"] = pending["registration"]
        player["pending"] = None
    registration = player["registration"]
    if registration and registration["registration_type"] == "loan" and day(registration["loan_end_date"], "loan_end_date") < as_of:
        effective = (day(registration["loan_end_date"], "loan_end_date") + timedelta(days=1)).isoformat()
        parent = registration["parent_squad_id"]
        player["history"].append({"audit_id": f"au-{len(player['history']) + 1:04d}", "action": "loan_return", "player_id": player["player_id"], "league_id": player["league_id"], "from_squad_id": registration["squad_id"], "to_squad_id": parent, "effective_date": effective, "state": "active", "who": "system", "when": now(), "why": "Loan ended"})
        player["registration"] = {**registration, "squad_id": parent, "parent_squad_id": None, "registration_type": "transfer", "loan_end_date": None, "start_date": effective, "role": "substitute"}
    store.put("player", player["player_id"], player)
    return player


def transfer(store, body):
    body = validate_transfer(body)
    request_id = body["request_id"]
    previous = store.get("transfer", request_id)
    if previous:
        check(previous["request"] == body, "request_id")
        return previous["response"], False
    lid, pid, source, dest = (body[k] for k in ("league_id", "player_id", "from_squad_id", "to_squad_id"))
    league = store.require("roster_league", lid, "LEAGUE_NOT_FOUND")
    player = store.require("player", pid)
    from_squad = store.require("squad", source)
    to_squad = store.require("squad", dest) if dest else None
    if from_squad["tenant_id"] != league["tenant_id"] or (to_squad and to_squad["tenant_id"] != league["tenant_id"]):
        fail(409, "CROSS_TENANT_TRANSFER")
    player = materialize(store, player, day(body["effective_date"], "effective_date"))
    registration = player["registration"]
    if player["league_id"] != lid or not registration or registration["squad_id"] != source or from_squad["league_id"] != lid:
        fail(409, "NOT_IN_SQUAD")
    if body["type"] != "release" and not league["transfer_window"]["open"] <= body["effective_date"] <= league["transfer_window"]["close"]:
        fail(409, "TRANSFER_WINDOW_CLOSED")
    if registration["registration_type"] == "loan":
        fail(409, "PLAYER_ON_LOAN")
    players = [p for p in store.all("player") if p["league_id"] == lid]
    if to_squad and sum(p["registration"] is not None and p["registration"]["squad_id"] == dest for p in players) >= league["max_squad_size"]:
        fail(409, "SQUAD_FULL")
    position = player["position"]
    left = sum(p["player_id"] != pid and p["position"] == position and p["registration"] is not None and p["registration"]["squad_id"] == source for p in players)
    if left < league["required_positions"].get(position, 0):
        fail(409, "MIN_POSITION_VIOLATION")
    blocking = next((m["match_id"] for m in live_matches(store, league) if source in (m["home_squad_id"], m["away_squad_id"])), None)
    state = "deferred_until_match_end" if blocking else "active"
    new = None if body["type"] == "release" else {"player_id": pid, "league_id": lid, "squad_id": dest, "parent_squad_id": source if body["type"] == "loan" else None, "registration_type": body["type"], "start_date": body["effective_date"], "loan_end_date": body["loan_end_date"] if body["type"] == "loan" else None, "role": "substitute"}
    audit = {"audit_id": f"au-{len(player['history']) + 1:04d}", "action": body["type"], "player_id": pid, "league_id": lid, "from_squad_id": source, "to_squad_id": dest, "effective_date": body["effective_date"], "state": state, "who": body["requested_by"], "when": now(), "why": body["reason"]}
    player["history"].append(audit)
    if blocking:
        player["pending"] = {"registration": new, "blocking_match_id": blocking}
    else:
        player["registration"] = new
    store.put("player", pid, player)
    response = {"request_id": request_id, "status": "accepted", "state": state, "blocking_match_id": blocking, "registration": new, "audit_entry": audit}
    store.put("transfer", request_id, {"request_id": request_id, "league_id": lid, "request": body, "response": response})
    return response, True


def history(store, player_id, as_of=None):
    requested = day(as_of, "as_of") if as_of else date.today()
    player = materialize(store, store.require("player", player_id), requested)
    current = deepcopy(player["initial_registration"])
    # Historical reads reconstruct location without deleting future audit entries.
    for entry in player["history"]:
        if entry["effective_date"] > requested.isoformat():
            continue
        if entry["state"] == "deferred_until_match_end" and player.get("pending"):
            continue
        if entry["action"] == "release":
            current = None
        else:
            current = {"squad_id": entry["to_squad_id"], "parent_squad_id": entry["from_squad_id"] if entry["action"] == "loan" else None}
    return {"player_id": player_id, "as_of": requested.isoformat(), "league_id": player["league_id"], "current_squad_id": current["squad_id"] if current else None, "parent_squad_id": current.get("parent_squad_id") if current else None, "history": player["history"]}
