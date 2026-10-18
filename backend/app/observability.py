"""NFR-6 Prometheus /metrics and NFR-9 right-to-erasure."""
from . import live, streaming
from .common import fail


def metrics(store):
    streams, kbps = streaming.media_stats()
    values = [
        ("nascon_active_ws_connections", "gauge", "Open WebSocket connections (scores and chat).", live.connections),
        ("nascon_active_streams_total", "gauge", "Media paths currently receiving a broadcast.", streams),
        ("nascon_stream_bitrate_kbps", "gauge", "Combined inbound broadcast bitrate in kbit/s.", kbps),
        ("nascon_dlq_depth", "gauge", "Notifications parked in the Dead-Letter Queue.", len(store.all("dlq"))),
        ("nascon_qr_scans_total", "counter", "QR scans recorded across all entities.", sum(r["scan_count"] for r in store.all("qr"))),
    ]
    lines = []
    for name, kind, text, value in values:
        lines += [f"# HELP {name} {text}", f"# TYPE {name} {kind}", f"{name} {value}"]
    return "\n".join(lines) + "\n"


def erase(store, user_id):
    """Anonymise the account and unlink every guest/viewer token that was merged into it."""
    user, player = store.get("user", user_id), store.get("player", user_id)
    linked_users = [u for u in store.all("user") if u.get("player_id") == user_id]
    if user is None and player is None:
        fail(404, "PLAYER_NOT_FOUND")
    for account in ([user] if user else []) + linked_users:
        account.update(name="Anonymized User", email=f"anon-{account['user_id']}@deleted.invalid")
        store.put("user", account["user_id"], account)
    if player:
        player["name"] = "Anonymized Player"
        store.put("player", user_id, player)
    for token in store.all("guest_token"):
        if token.get("merged_into") == user_id:
            store.remove("guest_token", token["guest_token"])
            store.remove("guest_merge", token["guest_token"])
    return {"user_id": user_id, "status": "anonymized", "pii_retained": False, "tokens_wiped": True}
