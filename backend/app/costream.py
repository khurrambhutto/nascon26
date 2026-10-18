"""FR-3.3 four-way co-stream sessions with deterministic host-drop handoff."""
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
