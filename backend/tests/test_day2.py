import hashlib
import hmac
import json
import threading

import app.common as common
from app import moderation, streaming

GUEST = {"Authorization": "Bearer guest_anon_tok_7721"}


def expected(entity_id, payload):
    message = entity_id + "|" + json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hmac.new(common.DAY2_SALT, message.encode(), hashlib.sha256).hexdigest()


def test_sign2_matches_appendix_a_recipe(monkeypatch):
    monkeypatch.setattr(common, "DAY2_SALT", b"T01D27ad7ffff12e")
    payload = {"action": "rotate", "player_id": "player-alpha-1", "status": "active", "stream_key": "sk_live_9f83a1b2c3d4e5f6"}
    message = 'sk_live_9f83a1b2c3d4e5f6|{"action":"rotate","player_id":"player-alpha-1","status":"active","stream_key":"sk_live_9f83a1b2c3d4e5f6"}'
    assert common.sign2("sk_live_9f83a1b2c3d4e5f6", payload) == hmac.new(b"T01D27ad7ffff12e", message.encode(), hashlib.sha256).hexdigest()


def test_reset_seeds_day2_baseline(client):
    qr = client.get("/audit/v1/qr/match-m1?inspect=true").json()
    assert qr["short_url"] == "http://localhost:8080/q/m1-live" and qr["scan_count"] == 0
    assert client.get("/audit/v1/score/match-m1").json()["home"]["score"] == 1
    assert client.get("/audit/v1/qr/player-alpha-1?inspect=true").json()["short_url"].endswith("/q/p-alpha-1")
    assert client.get("/audit/v1/qr/club-alpha-1?inspect=true").json()["short_url"].endswith("/q/c-alpha-1")


def test_stream_key_lifecycle(client):
    body = {"action": "generate", "player_id": "player-alpha-1", "label": "OBS-Main-Desk", "stream_key_override": "sk_live_9f83a1b2c3d4e5f6"}
    created = client.post("/audit/v1/stream/key", json=body)
    assert created.status_code == 201
    data = created.json()
    assert data["ingest_endpoints"]["rtmp"] == "rtmp://localhost:1935/live/sk_live_9f83a1b2c3d4e5f6"
    assert data["playback_url"] == "http://localhost:8888/live/sk_live_9f83a1b2c3d4e5f6/index.m3u8"
    assert data["signature"] == expected("sk_live_9f83a1b2c3d4e5f6", {"action": "generate", "player_id": "player-alpha-1", "status": "active", "stream_key": "sk_live_9f83a1b2c3d4e5f6"})
    rotated = client.post("/audit/v1/stream/key", json={"action": "rotate", "player_id": "player-alpha-1"})
    assert rotated.status_code == 200
    new_key = rotated.json()["stream_key"]
    assert new_key.startswith("sk_live_") and len(new_key) == 24
    old = client.post("/internal/media/auth", json={"action": "publish", "path": "live/sk_live_9f83a1b2c3d4e5f6"})
    assert old.status_code == 409 and old.json()["error"]["code"] == "KEY_REVOKED"
    assert client.post("/internal/media/auth", json={"action": "publish", "path": "live/" + new_key}).status_code == 200
    assert client.post("/internal/media/auth", json={"action": "publish", "path": "live/nope"}).status_code == 404
    assert client.post("/internal/media/auth", json={"action": "api", "path": ""}).status_code == 200
    revoked = client.post("/audit/v1/stream/key", json={"action": "revoke", "player_id": "player-alpha-1"}).json()
    assert revoked["status"] == "revoked" and revoked["ingest_endpoints"] is None and revoked["playback_url"] is None
    missing = client.post("/audit/v1/stream/key", json={"action": "revoke", "player_id": "player-alpha-1"})
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "STREAM_NOT_FOUND"


def test_stream_key_validation(client):
    assert client.post("/audit/v1/stream/key", json={"action": "pause", "player_id": "player-alpha-1"}).status_code == 400
    assert client.post("/audit/v1/stream/key", json={"action": "generate", "player_id": "player-alpha-1", "label": ""}).status_code == 400
    missing = client.post("/audit/v1/stream/key", json={"action": "generate", "player_id": "ghost"})
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "PLAYER_NOT_FOUND"


def test_media_helpers_without_media_server():
    assert streaming.media_stats() == (0, 0.0)
    streaming.sever("sk_live_unused")


COSTREAM = {"session_id": "cs-session-101", "host_player_id": "player-alpha-1",
            "participants": ["player-alpha-1", "player-alpha-2", "player-beta-1", "player-beta-2"], "layout": "grid_4", "simulate_host_drop": True}


def test_costream_host_drop_handoff_and_replay(client):
    first = client.post("/audit/v1/costream/session", json=COSTREAM)
    assert first.status_code == 201
    data = first.json()
    assert data["active_host"] == "player-alpha-2" and data["handoff_occurred"] is True and data["participant_count"] == 3
    assert data["active_participants"] == ["player-alpha-2", "player-beta-1", "player-beta-2"]
    assert data["chat_room_id"] == "chat-cs-session-101"
    assert data["composite_playback_url"] == "http://localhost:8888/costream/cs-session-101/index.m3u8"
    assert data["signature"] == expected("cs-session-101", {"active_host": "player-alpha-2", "handoff_occurred": True, "layout": "grid_4", "participant_count": 3, "session_id": "cs-session-101", "status": "active"})
    replay = client.post("/audit/v1/costream/session", json=COSTREAM)
    assert replay.status_code == 200 and replay.json() == data


def test_costream_rules(client):
    solo = {**COSTREAM, "session_id": "cs-session-1", "participants": ["player-alpha-1"]}
    ended = client.post("/audit/v1/costream/session", json=solo).json()
    assert ended["status"] == "terminated" and ended["active_host"] is None and ended["participant_count"] == 0
    stay = client.post("/audit/v1/costream/session", json={**COSTREAM, "session_id": "cs-session-2", "simulate_host_drop": False}).json()
    assert stay["active_host"] == "player-alpha-1" and stay["handoff_occurred"] is False and stay["participant_count"] == 4
    five = {**COSTREAM, "participants": COSTREAM["participants"] + ["demo-player-1-1"]}
    assert client.post("/audit/v1/costream/session", json=five).json()["error"]["code"] == "COSTREAM_SESSION_FULL"
    bad_layout = client.post("/audit/v1/costream/session", json={**COSTREAM, "layout": "grid_2"})
    assert bad_layout.status_code == 400 and bad_layout.json()["error"]["code"] == "UNSUPPORTED_LAYOUT"
    assert client.post("/audit/v1/costream/session", json={**COSTREAM, "participants": ["player-alpha-2"]}).status_code == 400
    assert client.post("/audit/v1/costream/session", json={**COSTREAM, "participants": ["ghost", "player-alpha-1"]}).status_code == 404
    assert client.post("/audit/v1/costream/session", json={**COSTREAM, "layout": "mosaic"}).status_code == 400


def test_qr_resolution_scan_count_and_dynamic_target(client):
    first = client.get("/audit/v1/qr/match-m1").json()
    assert first["scan_count"] == 1 and first["target_route"] == "/web/matches/match-m1/live"
    assert first["entity_type"] == "match" and first["entity_status"] == "in_progress"
    assert first["deep_link_uri"] == "nascon://entity/match-m1"
    assert first["signature"] == expected("match-m1", {"entity_id": "match-m1", "short_url": "http://localhost:8080/q/m1-live", "target_route": "/web/matches/match-m1/live"})
    assert client.get("/audit/v1/qr/match-m1?inspect=true").json()["scan_count"] == 1
    client.post("/audit/v1/events", json={"event_id": "e-end", "match_id": "match-m1", "type": "match_end", "payload": {}})
    after = client.get("/audit/v1/qr/match-m1").json()
    assert after["short_url"] == first["short_url"] and after["target_route"] == "/web/matches/match-m1/vod"
    assert after["signature"] != first["signature"]


def test_qr_postponed_notice_and_short_link(client):
    assert client.post("/audit/v1/events", json={"event_id": "e-pp", "match_id": "match-m1", "type": "match_postponed", "payload": {}}).status_code == 201
    assert client.get("/audit/v1/qr/match-m1").json()["target_route"] == "/web/matches/match-m1/notice?state=postponed"
    redirect = client.get("/q/m1-live", follow_redirects=False, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) Mobile"})
    assert redirect.status_code == 302 and redirect.headers["location"] == "/web/matches/match-m1/notice?state=postponed"
    assert client.get("/q/unknown", follow_redirects=False).json()["error"]["code"] == "QR_NOT_FOUND"
    assert client.post("/audit/v1/events", json={"event_id": "e-pp2", "match_id": "match-m1", "type": "match_cancelled", "payload": {}}).status_code == 201
    assert client.post("/audit/v1/events", json={"event_id": "e-pp3", "match_id": "match-m1", "type": "status_change", "payload": {"status": "completed"}}).status_code == 409


def test_qr_other_entities_and_exports(client):
    assert client.get("/audit/v1/qr/player-alpha-1").json()["target_route"] == "/web/players/player-alpha-1"
    assert client.get("/audit/v1/qr/club-alpha-2").json()["target_route"] == "/web/clubs/club-alpha-2"
    client.post("/audit/v1/costream/session", json=COSTREAM)
    assert client.get("/audit/v1/qr/cs-session-101").json()["target_route"] == "/web/costream/cs-session-101"
    assert client.get("/audit/v1/qr/match-nope").json()["error"]["code"] == "QR_NOT_FOUND"
    assert client.get("/audit/v1/qr/thing-1").status_code == 404
    svg = client.get("/audit/v1/qr/match-m1/export.svg")
    assert svg.headers["content-type"].startswith("image/svg+xml") and svg.text.startswith("<svg") and "q/m1-live" in svg.text
    png = client.get("/audit/v1/qr/match-m1/export.png")
    assert png.headers["content-type"] == "image/png" and png.content[:8] == b"\x89PNG\r\n\x1a\n"
    pdf = client.get("/audit/v1/qr/match-m1/export.pdf")
    assert pdf.headers["content-type"] == "application/pdf" and pdf.content.startswith(b"%PDF-")
    assert client.get("/audit/v1/qr/match-m1/export.gif").status_code == 404


def test_guest_writes_are_forbidden_server_side(client):
    for path in ("/api/v1/chat", "/audit/v1/checkout", "/audit/v1/events"):
        response = client.post(path, json={}, headers=GUEST)
        assert response.status_code == 403 and response.json()["error"]["code"] == "GUEST_WRITE_FORBIDDEN"
    assert client.post("/api/v1/chat", json={"chat_room_id": "r", "text": "hi"}).status_code == 403


MERGE = {"guest_token": "guest_anon_tok_7721", "registered_user_id": "player-alpha-1",
         "guest_cart": [{"variant_id": "var-jersey-001-L", "quantity": 1}], "test_write_block": True}


def test_cart_merge_and_network_replay(client):
    first = client.post("/audit/v1/guest/cart-merge", json=MERGE)
    assert first.status_code == 200
    data = first.json()
    assert data["write_block_verified"] is True and data["write_rejection_code"] == 403 and data["cart_merged"] is True
    assert data["active_cart"] == {"user_id": "player-alpha-1", "currency": "PKR", "total_items": 1, "subtotal_minor": 450000,
                                   "items": [{"variant_id": "var-jersey-001-L", "quantity": 1, "unit_price_minor": 450000}]}
    assert data["signature"] == expected("guest_anon_tok_7721", {"cart_merged": True, "guest_token": "guest_anon_tok_7721", "registered_user_id": "player-alpha-1", "total_items": 1, "write_rejection_code": 403})
    replay = client.post("/audit/v1/guest/cart-merge", json=MERGE)
    assert replay.status_code == 200 and replay.json()["active_cart"]["total_items"] == 1
    other = client.post("/audit/v1/guest/cart-merge", json={**MERGE, "registered_user_id": "player-alpha-2"})
    assert other.status_code == 409 and other.json()["error"]["code"] == "GUEST_TOKEN_ALREADY_MERGED"


def test_cart_merge_validation_order(client):
    assert client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_token": "tok"}).status_code == 400
    assert client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_cart": []}).status_code == 400
    assert client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_cart": [{"variant_id": "v", "quantity": 0}]}).status_code == 400
    assert client.post("/audit/v1/guest/cart-merge", json={**MERGE, "registered_user_id": "ghost"}).json()["error"]["code"] == "PLAYER_NOT_FOUND"
    assert client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_cart": [{"variant_id": "nope", "quantity": 1}]}).json()["error"]["code"] == "VARIANT_NOT_FOUND"
    stock = client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_cart": [{"variant_id": "var-jersey-001-M", "quantity": 2}]})
    assert stock.status_code == 409 and stock.json()["error"]["details"]["variant_id"] == "var-jersey-001-M"
    unblocked = client.post("/audit/v1/guest/cart-merge", json={**MERGE, "guest_token": "guest_x", "test_write_block": False}).json()
    assert unblocked["write_block_verified"] is False and unblocked["write_rejection_code"] is None


MODERATION = {"chat_room_id": "chat-cs-session-101", "check_dlq": True, "simulate_failed_notifications": 0, "incoming_messages": [
    {"message_id": "msg-1", "sender": "user-guest-1", "text": "Clean sports commentary"},
    {"message_id": "msg-2", "sender": "user-spammer", "text": "Watch this illegal stream b@d_w0rd now!"},
    {"message_id": "msg-3", "sender": "user-flooder", "text": "GOAL! AAAA!!!"}]}


def test_moderation_contract_example(client):
    data = client.post("/audit/v1/moderation/test", json=MODERATION).json()
    assert data["filtered_messages"] == [
        {"message_id": "msg-1", "sender": "user-guest-1", "text": "Clean sports commentary", "flagged": False, "reason": None},
        {"message_id": "msg-2", "sender": "user-spammer", "text": "Watch this illegal stream *** now!", "flagged": True, "reason": "profanity_leetspeak"}]
    assert data["kicked_users"] == ["user-spammer"] and data["muted_users"] == ["user-flooder"]
    assert data["dlq_status"] == {"queue_depth": 0, "undelivered_events": []}
    assert data["signature"] == expected("chat-cs-session-101", {"chat_room_id": "chat-cs-session-101", "flagged_count": 1, "kicked_users": ["user-spammer"], "queue_depth": 0})


def test_moderation_dlq_and_concurrency(client):
    data = client.post("/audit/v1/moderation/test", json={**MODERATION, "simulate_failed_notifications": 2}).json()
    assert data["dlq_status"]["queue_depth"] == 2 and data["dlq_status"]["undelivered_events"][0]["attempts"] == 3
    results = []
    threads = [threading.Thread(target=lambda: results.append(client.post("/audit/v1/moderation/test", json=MODERATION))) for _ in range(10)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert all(r.status_code == 200 and r.json()["kicked_users"] == ["user-spammer"] for r in results)
    assert "nascon_dlq_depth 2" in client.get("/metrics").text
    assert client.post("/audit/v1/moderation/test", json={"chat_room_id": "", "incoming_messages": []}).status_code == 400


def test_leetspeak_variants_and_floods():
    for word in ("p.0.r.n", "s!c@m", "f*u*c*k", "a$$h0le", "bad_word", "idiot_spam", "SH1T", "b1tch"):
        assert moderation.mask("hey " + word + "!")[0] == "hey ***!"
    assert moderation.is_flood("wow???") and moderation.is_flood("nooooo") and not moderation.is_flood("Great goal!!")
    filtered, kicked, muted = moderation.moderate([{"message_id": str(i), "sender": "u", "text": "hi"} for i in range(5)])
    assert len(filtered) == 3 and muted == ["u"] and kicked == []


def test_metrics_and_privacy(client):
    client.get("/audit/v1/qr/match-m1")
    metrics = client.get("/metrics")
    assert metrics.headers["content-type"] == "text/plain; version=0.0.4"
    for name in ("nascon_active_ws_connections", "nascon_active_streams_total", "nascon_stream_bitrate_kbps", "nascon_dlq_depth", "nascon_qr_scans_total 1"):
        assert name in metrics.text
    client.post("/audit/v1/guest/cart-merge", json=MERGE)
    erased = client.delete("/audit/v1/privacy/user/player-alpha-1")
    assert erased.json() == {"user_id": "player-alpha-1", "status": "anonymized", "pii_retained": False, "tokens_wiped": True}
    assert client.delete("/audit/v1/privacy/user/ghost").status_code == 404


def test_guest_web_player_flow(client, login):
    page = client.get("/q/m1-live")
    assert page.status_code == 200 and str(page.url).endswith("/web/matches/match-m1/live")
    for testid in ("guest-video-player", "live-score-banner", "pinned-merch-card", "add-to-guest-cart-btn", "guest-cart-count",
                   "chat-message-list", "chat-input-field", "chat-send-btn", "progressive-auth-modal"):
        assert f'data-testid="{testid}"' in page.text
    for path in ("/web/costream/cs-session-101", "/web/players/player-alpha-1", "/web/clubs/club-alpha-1"):
        assert client.get(path).status_code == 200
    token = client.post("/api/v1/guest/session").json()["guest_token"]
    staged = client.post("/api/v1/guest/cart", json={"variant_id": "var-jersey-001-L"}, headers={"Authorization": "Bearer " + token})
    assert staged.json()["count"] == 1
    assert client.post("/api/v1/guest/cart", json={"variant_id": "x"}, headers={"Authorization": "Bearer " + token}).status_code == 404
    assert client.post("/api/v1/guest/cart", json={"variant_id": "x"}).status_code == 401
    match = client.get("/api/v1/public/matches/match-m1").json()
    assert match["score_text"] == "1 - 0" and match["pinned_product"]["product_id"] == "prod-jersey-001"


def test_member_chat_and_websockets(client, login):
    headers = login("viewer")
    with client.websocket_connect("/ws/chat/chat-match-m1") as chat, client.websocket_connect("/ws/matches/match-m1") as score:
        assert chat.receive_json()["messages"] == []
        assert score.receive_json()["score_text"] == "1 - 0"
        sent = client.post("/api/v1/chat", json={"chat_room_id": "chat-match-m1", "text": "what a sc@m"}, headers=headers)
        assert sent.status_code == 201 and sent.json()["text"] == "what a ***" and sent.json()["sequence"] == 1
        assert any(m["text"] == "what a ***" for _ in range(3) for m in chat.receive_json()["messages"])
        client.post("/audit/v1/events", json={"event_id": "ws-goal", "match_id": "match-m1", "type": "score", "payload": {"team_id": "club-alpha-2", "event_type": "goal"}})
        assert any(score.receive_json()["score_text"] == "1 - 1" for _ in range(5))
    assert client.get("/api/v1/chat/chat-match-m1").json()["sequence"] == 1
    flood = client.post("/api/v1/chat", json={"chat_room_id": "chat-match-m1", "text": "GOOOOAL"}, headers=headers)
    assert flood.status_code == 403  # Prior profanity kicked this sender from this room.


def test_athlete_profile_and_follow_graph(client, login):
    viewer, manager = login("viewer"), login("manager")
    profile = client.get("/api/v1/athletes/player-alpha-1").json()
    assert profile["clubs"] == [{"club_id": "club-alpha-1", "name": "Alpha Lions"}] and profile["career"]["played"] >= 1
    assert client.get("/api/v1/athletes/ghost").status_code == 404
    results = []
    threads = [threading.Thread(target=lambda h=h: results.append(client.put("/api/v1/athletes/player-alpha-1/follow", headers=h)))
               for h in [viewer, manager] * 5]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert all(r.status_code == 200 for r in results)
    assert client.get("/api/v1/athletes/player-alpha-1").json()["follower_count"] == 2
    assert client.delete("/api/v1/athletes/player-alpha-1/follow", headers=viewer).json()["follower_count"] == 1
    assert client.delete("/api/v1/athletes/player-alpha-1/follow", headers=viewer).json()["follower_count"] == 1
    assert client.put("/api/v1/athletes/player-alpha-1/follow", headers=GUEST).json()["error"]["code"] == "GUEST_WRITE_FORBIDDEN"
    assert client.put("/api/v1/athletes/ghost/follow", headers=viewer).status_code == 404


class FakeReply:
    def __init__(self, data):
        self.data = json.dumps(data).encode()

    def read(self, *args):
        return self.data

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_media_server_kick_and_stats(monkeypatch):
    calls = []

    def fake_urlopen(target, timeout=None):
        url = target if isinstance(target, str) else target.full_url
        calls.append(url)
        if url.endswith("/v3/paths/list"):
            return FakeReply({"items": [{"name": "live/k", "ready": True, "bytesReceived": 125000}, {"name": "live/x", "ready": False}]})
        if url.endswith("/list"):
            return FakeReply({"items": [{"id": "c1", "path": "live/sk_live_a"}, {"id": "c2", "path": "live/other"}]})
        return FakeReply({})

    monkeypatch.setattr(streaming, "MEDIA_API", "http://media:9997")
    monkeypatch.setattr(streaming.urllib.request, "urlopen", fake_urlopen)
    streaming.kick_sessions("sk_live_a")
    assert "http://media:9997/v3/rtmpconns/kick/c1" in calls and not any("kick/c2" in c for c in calls)
    monkeypatch.setattr(streaming, "last_sample", [0, 0.0])
    assert streaming.media_stats() == (1, 0.0)
    count, kbps = streaming.media_stats()
    assert count == 1 and kbps == 0.0

    def broken(target, timeout=None):
        raise OSError("media down")
    monkeypatch.setattr(streaming.urllib.request, "urlopen", broken)
    streaming.kick_sessions("sk_live_a")
    assert streaming.media_stats() == (0, 0.0)
    monkeypatch.setattr(streaming.threading, "Thread", lambda target, args, daemon: type("T", (), {"start": lambda self: target(*args)})())
    streaming.sever("sk_live_a")


def test_costream_host_controls_invites_and_live_view(client, login):
    host = login("player")  # u-player is player-alpha-1
    client.post("/audit/v1/costream/session", json={**COSTREAM, "participants": ["player-alpha-1", "player-alpha-2"], "layout": "grid_2", "simulate_host_drop": False})
    client.post("/audit/v1/stream/key", json={"action": "generate", "player_id": "player-alpha-2"})
    view = client.get("/api/v1/public/costream/cs-session-101").json()
    assert view["active_host"] == "player-alpha-1" and view["streams"][1]["stream_key"].startswith("sk_live_")
    invite = client.post("/api/v1/costream/cs-session-101/invites", json={"player_id": "player-beta-1"}, headers=host)
    assert invite.status_code == 201
    token = invite.json()["invite_token"]
    assert client.post("/api/v1/costream/cs-session-101/join", json={"player_id": "player-beta-2", "invite_token": token}).status_code == 403
    joined = client.post("/api/v1/costream/cs-session-101/join", json={"player_id": "player-beta-1", "invite_token": token}).json()
    assert joined["participants"] == ["player-alpha-1", "player-alpha-2", "player-beta-1"]
    control = lambda action, player: client.post("/api/v1/costream/cs-session-101/control", json={"action": action, "player_id": player}, headers=host)
    assert control("mute", "player-alpha-2").json()["streams"][1]["muted"] is True
    assert control("unmute", "player-alpha-2").json()["muted"] == []
    assert control("spotlight", "player-beta-1").json()["layout"] == "spotlight"
    kicked = control("kick", "player-beta-1").json()
    assert "player-beta-1" not in kicked["participants"] and kicked["kicked"] == ["player-beta-1"] and kicked["spotlight"] is None
    assert client.post("/api/v1/costream/cs-session-101/join", json={"player_id": "player-beta-1", "invite_token": token}).status_code == 403
    assert control("kick", "ghost").status_code == 404
    assert control("dance", "player-alpha-2").status_code == 400
    assert client.post("/api/v1/costream/cs-session-101/control", json={"action": "mute", "player_id": "player-alpha-2"}, headers=login("viewer")).status_code == 403
    assert client.post("/api/v1/costream/cs-session-101/control", json={"action": "mute", "player_id": "player-alpha-2"}, headers=login("admin")).status_code == 200
    assert client.get("/api/v1/public/costream/cs-none").status_code == 404
    with client.websocket_connect("/ws/costream/cs-session-101") as socket:
        assert socket.receive_json()["type"] == "costream"


def test_costream_join_refills_terminated_session_and_capacity(client, login):
    client.post("/audit/v1/costream/session", json={**COSTREAM, "participants": ["player-alpha-1"]})
    host_token = client.post("/api/v1/costream/cs-session-101/invites", json={"player_id": "player-alpha-2"}, headers=login("admin"))
    assert host_token.status_code == 403  # terminated session has no host to act for
    client.post("/audit/v1/costream/session", json={**COSTREAM, "session_id": "cs-full", "simulate_host_drop": False})
    token = client.post("/api/v1/costream/cs-full/invites", json={"player_id": "demo-player-1-1"}, headers=login("player")).json()["invite_token"]
    assert client.post("/api/v1/costream/cs-full/join", json={"player_id": "demo-player-1-1", "invite_token": token}).status_code == 409
    assert client.post("/api/v1/costream/cs-full/invites", json={"player_id": "ghost"}, headers=login("player")).status_code == 404


def test_in_app_go_live_and_match_page_stream(client, login):
    live = client.post("/api/v1/me/stream-key", json={}, headers=login("player")).json()
    assert live["status"] == "active" and live["ingest_endpoints"]["webrtc"].endswith("/whip")
    assert client.post("/api/v1/me/stream-key", json={}, headers=login("viewer")).status_code == 403
    match = client.get("/api/v1/public/matches/match-m1").json()
    assert match["live_stream"] == {"player_id": "player-alpha-1", "stream_key": live["stream_key"]}
    assert client.post("/api/v1/me/stream-key", json={"action": "revoke"}, headers=login("player")).json()["status"] == "revoked"
    assert client.get("/api/v1/public/matches/match-m1").json()["live_stream"] is None


def test_qr_poster_page(client):
    page = client.get("/web/qr")
    assert page.status_code == 200 and "/audit/v1/qr/match-m1/export.svg" in page.text and "/q/m1-live" in page.text
