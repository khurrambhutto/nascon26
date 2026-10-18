"""FR-3.2 stream key lifecycle (generate / rotate / revoke) and media-server publish auth."""
import json
import logging
import os
import secrets
import threading
import time
import urllib.request

from .common import check, fail, obj, sign2

log = logging.getLogger(__name__)
MEDIA_API = os.environ.get("MEDIA_API_URL", "")  # e.g. http://media:9997 inside Compose


def player_exists(store, player_id):
    return store.get("player", player_id) is not None


def stream_key(store, body):
    obj(body)
    action = body.get("action")
    check(action in ("generate", "rotate", "revoke"), "action")
    player_id = body.get("player_id")
    check(isinstance(player_id, str) and player_id, "player_id")
    label = body.get("label", "default")
    check(isinstance(label, str) and 1 <= len(label) <= 64, "label")
    override = body.get("stream_key_override")
    check(override is None or (isinstance(override, str) and 1 <= len(override) <= 128), "stream_key_override")
    if not player_exists(store, player_id):
        fail(404, "PLAYER_NOT_FOUND")

    current = store.get("player_stream", player_id)
    if action == "revoke":
        if not current:
            fail(404, "STREAM_NOT_FOUND")
        key = current["stream_key"]
        record = store.get("stream_key", key)
        record["status"] = "revoked"
        store.put("stream_key", key, record)
        store.remove("player_stream", player_id)
        sever(key)
        return response(action, record), False

    # generate and rotate both invalidate whatever key the player had before.
    if current:
        old = store.get("stream_key", current["stream_key"])
        old["status"] = "revoked"
        store.put("stream_key", old["stream_key"], old)
        sever(old["stream_key"])
    key = override or "sk_live_" + secrets.token_hex(8)
    record = {"stream_key": key, "player_id": player_id, "label": label, "status": "active"}
    store.put("stream_key", key, record)
    store.put("player_stream", player_id, {"stream_key": key, "player_id": player_id})
    return response(action, record), action == "generate"


def response(action, record):
    key, active = record["stream_key"], record["status"] == "active"
    signed = {"action": action, "player_id": record["player_id"], "status": record["status"], "stream_key": key}
    return {
        "player_id": record["player_id"],
        "label": record["label"],
        "stream_key": key,
        "status": record["status"],
        "ingest_endpoints": {"rtmp": f"rtmp://localhost:1935/live/{key}", "webrtc": f"http://localhost:8889/live/{key}/whip"} if active else None,
        "playback_url": f"http://localhost:8888/live/{key}/index.m3u8" if active else None,
        "signature": sign2(key, signed),
    }


def media_auth(store, body):
    """MediaMTX HTTP auth hook: only active keys may publish or play under live/."""
    body = body if isinstance(body, dict) else {}
    action, path = body.get("action", ""), body.get("path", "")
    if action not in ("publish", "read", "playback") or not path.startswith("live/"):
        return {"allowed": True}
    record = store.get("stream_key", path.split("/", 1)[1])
    if record is None:
        fail(404, "STREAM_NOT_FOUND")
    if record["status"] != "active":
        fail(409, "KEY_REVOKED")
    return {"allowed": True}


def sever(key):
    """Kick live RTMP/WebRTC sessions on a revoked key (best effort, off the request path)."""
    if MEDIA_API:
        threading.Thread(target=kick_sessions, args=(key,), daemon=True).start()


def kick_sessions(key):
    for kind in ("rtmpconns", "webrtcsessions"):
        try:
            with urllib.request.urlopen(f"{MEDIA_API}/v3/{kind}/list", timeout=1) as reply:
                items = json.load(reply).get("items", [])
            for item in items:
                if item.get("path") == "live/" + key:
                    urllib.request.urlopen(urllib.request.Request(f"{MEDIA_API}/v3/{kind}/kick/{item['id']}", method="POST"), timeout=1)
        except Exception as error:  # media server is optional for the API contract
            log.warning("Could not sever %s sessions: %s", kind, error)


def media_stats():
    """Active publishers and their combined inbound bitrate, read from the MediaMTX API."""
    if not MEDIA_API:
        return 0, 0.0
    try:
        with urllib.request.urlopen(f"{MEDIA_API}/v3/paths/list", timeout=0.5) as reply:
            items = json.load(reply).get("items", [])
    except Exception:
        return 0, 0.0
    ready = [item for item in items if item.get("ready")]
    received, moment = sum(item.get("bytesReceived", 0) for item in ready), time.monotonic()
    previous_bytes, previous_moment = last_sample
    last_sample[:] = [received, moment]
    elapsed = moment - previous_moment
    kbps = max(received - previous_bytes, 0) * 8 / 1000 / elapsed if previous_moment and elapsed > 0 else 0.0
    return len(ready), round(kbps, 1)


last_sample = [0, 0.0]  # bytes received and time of the previous /metrics scrape
