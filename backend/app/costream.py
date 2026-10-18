"""FR-3.3 four-way co-stream sessions with deterministic host-drop handoff."""
import hmac

from .common import check, fail, obj, sign2

LAYOUT_LIMITS = {"solo": 1, "grid_2": 2, "grid_4": 4, "spotlight": 4}
MAX_BROADCASTERS = 4


def session(store, body):
    # 1. Schema
    obj(body)
    session_id, host = body.get("session_id"), body.get("host_player_id")
    participants = body.get("participants")
    layout = body.get("layout", "grid_4")
    drop = body.get("simulate_host_drop", False)
    check(isinstance(session_id, str) and session_id, "session_id")
    check(isinstance(host, str) and host, "host_player_id")
    check(isinstance(participants, list) and participants and all(isinstance(p, str) and p for p in participants)
          and len(set(participants)) == len(participants), "participants")
    check(layout in LAYOUT_LIMITS, "layout")
    check(isinstance(drop, bool), "simulate_host_drop")
    # 2. Every player exists, and the host is one of the participants
    for player_id in [host, *participants]:
        if store.get("player", player_id) is None:
            fail(404, "PLAYER_NOT_FOUND")
    check(host in participants, "host_player_id")
    # 3. Capacity
    if len(participants) > MAX_BROADCASTERS:
        fail(409, "COSTREAM_SESSION_FULL")
    # 5. Handoff: the host leaves and the next participant in array order takes over
    active = [p for p in participants if p != host] if drop else list(participants)
    # 4. Layout must fit the people who are still broadcasting
    if len(active) > LAYOUT_LIMITS[layout]:
        fail(400, "UNSUPPORTED_LAYOUT")

    request = {"session_id": session_id, "host_player_id": host, "participants": participants, "layout": layout, "simulate_host_drop": drop}
    previous = store.get("costream", session_id)
    if previous and previous["request"] == request:
        return previous["response"], False

    status = "active" if active else "terminated"
    signed = {"active_host": active[0] if active else None, "handoff_occurred": drop and bool(active), "layout": layout,
              "participant_count": len(active), "session_id": session_id, "status": status}
    response = {
        "session_id": session_id,
        "status": status,
        "active_host": signed["active_host"],
        "handoff_occurred": signed["handoff_occurred"],
        "participant_count": len(active),
        "active_participants": active,
        "layout": layout,
        "composite_playback_url": f"http://localhost:8888/costream/{session_id}/index.m3u8" if active else None,
        "chat_room_id": "chat-" + session_id,
        "signature": sign2(session_id, signed),
    }
    store.put("costream", session_id, {"request": request, "response": response})
    return response, True


# ---------- live session state and host moderation controls ----------

def state(store, session_id):
    """Mutable live state, seeded from the session response (the signed audit response never changes)."""
    record = store.get("costream", session_id)
    if record is None:
        fail(404, "STREAM_NOT_FOUND")
    response = record["response"]
    return record, record.get("state") or {"active_host": response["active_host"], "participants": list(response["active_participants"]),
                                            "layout": response["layout"], "muted": [], "spotlight": None, "kicked": [], "status": response["status"]}


def save(store, session_id, record, live_state):
    record["state"] = live_state
    store.put("costream", session_id, record)
    return view(store, session_id, live_state)


def view(store, session_id, live_state):
    """Session as the viewer surfaces need it: who is live, with which playback path."""
    def stream_of(player_id):
        current = store.get("player_stream", player_id)
        return current["stream_key"] if current else None
    return {"session_id": session_id, **live_state, "chat_room_id": "chat-" + session_id,
            "streams": [{"player_id": p, "stream_key": stream_of(p), "muted": p in live_state["muted"]} for p in live_state["participants"]]}


def invite(store, session_id, host_player, player_id):
    record, live_state = state(store, session_id)
    require_host(live_state, host_player)
    if store.get("player", player_id) is None:
        fail(404, "PLAYER_NOT_FOUND")
    token = sign2(session_id, {"invitee": player_id, "session_id": session_id})
    return {"session_id": session_id, "player_id": player_id, "invite_token": token}


def join(store, session_id, body):
    obj(body)
    player_id, token = body.get("player_id"), body.get("invite_token")
    check(isinstance(player_id, str) and isinstance(token, str), "invite_token")
    record, live_state = state(store, session_id)
    if not hmac.compare_digest(token, sign2(session_id, {"invitee": player_id, "session_id": session_id})):
        fail(403, "FORBIDDEN", reason="invalid_invite")
    if player_id in live_state["kicked"]:
        fail(403, "FORBIDDEN", reason="kicked")
    if player_id not in live_state["participants"]:
        if len(live_state["participants"]) >= MAX_BROADCASTERS:
            fail(409, "COSTREAM_SESSION_FULL")
        live_state["participants"].append(player_id)
    if live_state["active_host"] is None:
        live_state.update(active_host=player_id, status="active")
    return save(store, session_id, record, live_state)


def require_host(live_state, player):
    if player is None or live_state["active_host"] != player:
        fail(403, "FORBIDDEN", reason="host_only")


def control(store, session_id, host_player, body):
    """Host moderation: mute / unmute audio, spotlight a broadcaster, kick a participant."""
    obj(body)
    action, target = body.get("action"), body.get("player_id")
    check(action in ("mute", "unmute", "spotlight", "kick"), "action")
    check(isinstance(target, str), "player_id")
    record, live_state = state(store, session_id)
    require_host(live_state, host_player)
    if target not in live_state["participants"]:
        fail(404, "PLAYER_NOT_FOUND")
    if action == "mute" and target not in live_state["muted"]:
        live_state["muted"].append(target)
    elif action == "unmute" and target in live_state["muted"]:
        live_state["muted"].remove(target)
    elif action == "spotlight":
        live_state.update(spotlight=target, layout="spotlight")
    elif action == "kick":
        check(target != host_player, "player_id")
        live_state["participants"].remove(target)
        live_state["kicked"].append(target)
        if live_state["spotlight"] == target:
            live_state["spotlight"] = None
    return save(store, session_id, record, live_state)
