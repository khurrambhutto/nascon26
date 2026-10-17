"""Ordered, idempotent offline replay with a savepoint per action."""
from .common import DomainError, check, identifier, integer, obj, timestamp
from .roster import transfer, validate_transfer
from .scoring import append_event, validate_event


def sync(store, body):
    obj(body)
    device = identifier(body.get("device_id"), "device_id")
    actions = body.get("actions")
    check(isinstance(actions, list) and 1 <= len(actions) <= 100, "actions")
    for action in actions:
        obj(action, "actions")
        identifier(action.get("action_id"), "action_id")
        check(action.get("type") in ("score_event", "roster_transfer"), "type")
        timestamp(action.get("queued_at"), "queued_at")
        integer(action.get("base_version"), "base_version", 0)
        obj(action.get("payload"), "payload")
    ordered = sorted(enumerate(actions), key=lambda pair: (pair[1]["queued_at"], pair[0]))
    results = [None] * len(actions)
    summary = {"applied": 0, "rejected": 0, "duplicate": 0}
    for processed, (index, action) in enumerate(ordered, 1):
        key = device + "|" + action["action_id"]
        payload = action["payload"]
        event_action = action["type"] == "score_event"

        def version():
            if event_action:
                match = store.get("match", payload.get("match_id", ""))
                return len(match["events"]) if match else 0
            player = store.get("player", payload.get("player_id", ""))
            return len(player["history"]) if player else 0

        current = version()
        status, resolution, error, signature = "applied", "appended", None, None
        try:
            with store.connection.begin_nested():
                previous = store.get("offline", key)
                duplicate_event = store.get("event", payload.get("event_id", "")) if event_action else None
                if previous:
                    status, resolution = "duplicate", "skipped_duplicate"
                elif duplicate_event and duplicate_event["request"] == payload:
                    status, resolution = "duplicate", "skipped_duplicate"
                else:
                    if event_action:
                        validate_event(payload)
                    else:
                        validate_transfer(payload)
                    if event_action:
                        store.require("match", payload.get("match_id", ""))
                    else:
                        store.require("roster_league", payload.get("league_id", ""), "LEAGUE_NOT_FOUND")
                        store.require("player", payload.get("player_id", ""))
                        store.require("squad", payload.get("from_squad_id", ""))
                        if payload.get("to_squad_id"):
                            store.require("squad", payload["to_squad_id"])
                    if action["base_version"] > current:
                        raise DomainError(409, "INVALID_BASE_VERSION")
                    response, created = append_event(store, payload) if event_action else transfer(store, payload)
                    if not created:
                        status, resolution = "duplicate", "skipped_duplicate"
                    else:
                        resolution = "rebased_append" if action["base_version"] < current else "appended"
                        signature = response["signature"] if event_action else None
        except DomainError as exc:
            status, resolution, error = "rejected", "server_wins", exc.body["error"]["code"]
        result = {"action_id": action["action_id"], "status": status, "resolution": resolution, "error_code": error, "processed_order": processed, "server_version": version(), "signature": signature}
        store.put("offline", key, result)
        summary[status] += 1
        results[index] = result
    return {"device_id": device, "results": results, "summary": summary}
