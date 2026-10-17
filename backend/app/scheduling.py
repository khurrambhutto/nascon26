"""Venue, team, player and rest-window conflicts for manual rescheduling."""
from datetime import timedelta
from uuid import uuid4

from .common import check, fail, identifier, integer, now, obj, timestamp
from .scoring import append_event, project


def conflicts(store, match, scheduled_at, duration_minutes, venue_id):
    start = timestamp(scheduled_at, "scheduled_at")
    end = start + timedelta(minutes=duration_minutes)
    teams = {match["home_team_id"], match["away_team_id"]}
    league = store.get("league", match.get("league_id", ""), {})
    rest = league.get("minimum_rest_minutes", 0)
    found = []
    venue = store.get("venue", venue_id) if venue_id else None
    if venue_id and venue is None:
        fail(404, "VENUE_NOT_FOUND")
    if venue:
        windows = venue.get("availability", [])
        if windows and not any(timestamp(w["start"], "start") <= start and end <= timestamp(w["end"], "end") for w in windows):
            found.append({"type": "venue_unavailable", "venue_id": venue_id, "match_id": match["match_id"]})

    def players(team_ids):
        squads = {s["squad_id"] for s in store.all("squad") if s["club_id"] in team_ids}
        return {p["player_id"] for p in store.all("player") if p.get("registration") and p["registration"]["squad_id"] in squads}

    participants = players(teams)
    for other in store.all("match"):
        if other["match_id"] == match["match_id"] or other.get("tenant_id") != match.get("tenant_id") or not other.get("scheduled_at"):
            continue
        if other.get("operational_status") in ("cancelled", "abandoned"):
            continue
        other_start = timestamp(other["scheduled_at"], "scheduled_at")
        other_end = other_start + timedelta(minutes=other.get("duration_minutes", 90))
        overlap = start < other_end and other_start < end
        shared = teams & {other["home_team_id"], other["away_team_id"]}
        if overlap and venue_id and venue_id == other.get("venue_id"):
            found.append({"type": "venue_double_booking", "venue_id": venue_id, "match_id": other["match_id"]})
        if overlap and shared:
            found.append({"type": "team_overlap", "team_ids": sorted(shared), "match_id": other["match_id"]})
        if shared and not overlap and start < other_end + timedelta(minutes=rest) and other_start < end + timedelta(minutes=rest):
            found.append({"type": "minimum_rest", "team_ids": sorted(shared), "match_id": other["match_id"]})
        if overlap:
            shared_players = participants & players({other["home_team_id"], other["away_team_id"]})
            if shared_players:
                found.append({"type": "player_overlap", "player_ids": sorted(shared_players), "match_id": other["match_id"]})
    return found


def reschedule(store, match_id, body):
    obj(body)
    timestamp(body.get("scheduled_at"), "scheduled_at")
    duration = integer(body.get("duration_minutes", 90), "duration_minutes", 1, 1440)
    venue_id = identifier(body.get("venue_id"), "venue_id")
    match = store.require("match", match_id)
    collisions = conflicts(store, match, body["scheduled_at"], duration, venue_id)
    if collisions:
        fail(409, "SCHEDULE_CONFLICT", conflicts=collisions)
    match.update(scheduled_at=body["scheduled_at"], duration_minutes=duration, venue_id=venue_id, venue=store.require("venue", venue_id)["name"])
    store.put("match", match_id, match)
    return {"match_id": match_id, "scheduled_at": match["scheduled_at"], "venue_id": venue_id, "conflicts": []}


def change_status(store, match_id, body):
    requested = body.get("status")
    transitions = {"upcoming": {"in_progress", "postponed", "cancelled"}, "in_progress": {"completed", "postponed", "abandoned"}, "postponed": {"upcoming", "in_progress", "cancelled"}, "completed": set(), "cancelled": set(), "abandoned": set()}
    check(isinstance(requested, str) and requested in transitions, "status")
    match = store.require("match", match_id)
    projection = project(store, match_id)
    current = match.get("operational_status") or projection["status"]
    if requested not in transitions[current]:
        fail(409, "ILLEGAL_TRANSITION")
    if requested in ("in_progress", "completed") and projection["status"] != requested:
        append_event(store, {"event_id": "status-"+uuid4().hex, "match_id": match_id, "type": "match_start" if requested == "in_progress" else "match_end", "payload": {}})
        match = store.require("match", match_id)
    match["operational_status"] = requested
    invalidated = []
    if requested == "postponed":
        # Dependent bracket matches cannot retain a confirmed time after postponement.
        for downstream in store.all("match"):
            if match_id in downstream.get("depends_on_matches", []):
                downstream["scheduled_at"] = None
                downstream["operational_status"] = "postponed"
                store.put("match", downstream["match_id"], downstream)
                invalidated.append(downstream["match_id"])
    store.put("match", match_id, match)
    collisions = conflicts(store, match, match["scheduled_at"], match.get("duration_minutes", 90), match.get("venue_id")) if match.get("scheduled_at") else []
    return {"match_id": match_id, "status": requested, "downstream_invalidated": invalidated, "conflicts": collisions}
