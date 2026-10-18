"""Phase II HTTP and WebSocket routes. Business rules live in the domain modules."""
import asyncio
import time
from collections import defaultdict, deque

from fastapi import APIRouter, Body, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, RedirectResponse, Response

from . import auth, costream, live, moderation, observability, qr_guest, scoring, streaming
from .common import DomainError, check, fail, now, obj
from .storage import reader, transaction

router = APIRouter()
GUEST_ROOM_HISTORY = 100


def created_or_ok(result):
    response, created = result
    return JSONResponse(response, status_code=201 if created else 200)


# ---------- audit contract (/audit/v1/*) ----------

@router.post("/audit/v1/stream/key")
def stream_key(body: dict = Body(...)):
    with transaction() as db:
        return created_or_ok(streaming.stream_key(db, body))


@router.post("/audit/v1/costream/session")
def costream_session(body: dict = Body(...)):
    with transaction() as db:
        result = costream.session(db, body)
    live.bump("chat:" + result[0]["chat_room_id"])
    return created_or_ok(result)


@router.get("/audit/v1/qr/{entity_id}/export.{fmt}")
def qr_export(entity_id: str, fmt: str):
    with reader() as db:
        content, media_type = qr_guest.export(db, entity_id, fmt)
    return Response(content, media_type=media_type)


@router.get("/audit/v1/qr/{entity_id}")
def qr_resolve(entity_id: str, request: Request, inspect: bool = False):
    if inspect:
        with reader() as db:
            return qr_guest.resolve(db, entity_id, inspect=True)
    with transaction() as db:
        return qr_guest.resolve(db, entity_id, user_agent=request.headers.get("user-agent"))


@router.get("/q/{short_code}")
def short_link(short_code: str, request: Request):
    with transaction() as db:
        route = qr_guest.short_link(db, short_code, request.headers.get("user-agent"))
    return RedirectResponse(route, status_code=302)


@router.post("/audit/v1/guest/cart-merge")
def guest_cart_merge(body: dict = Body(...)):
    with transaction() as db:
        return qr_guest.cart_merge(db, body)


@router.post("/audit/v1/moderation/test")
def moderation_test(body: dict = Body(...)):
    room, messages, failed = moderation.validate(body)
    filtered, kicked, muted = moderation.moderate(messages)
    if failed:
        with transaction() as db:
            for _ in range(failed):
                moderation.dead_letter(db, "notification.push", room, "simulated downstream failure")
            dlq = moderation.dlq_status(db)
    else:
        with reader() as db:
            dlq = moderation.dlq_status(db)
    return moderation.response(room, filtered, kicked, muted, dlq)


@router.get("/metrics")
def metrics():
    with reader() as db:
        text = observability.metrics(db)
    return Response(text, headers={"content-type": "text/plain; version=0.0.4"})


@router.delete("/audit/v1/privacy/user/{user_id}")
def privacy_erase(user_id: str):
    with transaction() as db:
        return observability.erase(db, user_id)


@router.post("/internal/media/auth")
def media_auth(body: dict = Body(default={})):
    with reader() as db:
        return streaming.media_auth(db, body)


# ---------- guest + member app API ----------

def bearer(request):
    header = request.headers.get("authorization", "")
    return header[7:] if header.startswith("Bearer ") else None


@router.post("/api/v1/guest/session", status_code=201)
def guest_session():
    with transaction() as db:
        return qr_guest.new_guest(db)


@router.post("/api/v1/guest/cart")
def guest_cart(request: Request, body: dict = Body(...)):
    token = bearer(request)
    if not qr_guest.is_guest(token):
        fail(401, "UNAUTHENTICATED")
    with transaction() as db:
        return qr_guest.stage(db, token, body)


def public_match(db, match_id):
    match = db.require("match", match_id, "MATCH_NOT_FOUND")
    score = scoring.project(db, match_id)
    product = db.get("product", match.get("pinned_product_id") or "")
    names = {side: db.get("club", match[side + "_team_id"], {}).get("name", match[side + "_team_id"]) for side in ("home", "away")}
    return {"match_id": match_id, "status": scoring.effective_status(match, score["status"]),
            "home_name": names["home"], "away_name": names["away"],
            "home_score": score["home"]["score"], "away_score": score["away"]["score"],
            "score_text": f"{score['home']['score']} - {score['away']['score']}",
            "pinned_product": {"product_id": product["product_id"], "name": product["name"],
                               "variants": [db.get("variant", v) for v in product["variant_ids"]]} if product else None}


@router.get("/api/v1/public/matches/{match_id}")
def guest_match(match_id: str):
    with reader() as db:
        return public_match(db, match_id)


def room_messages(db, room_id):
    return db.get("chat", room_id, {"room_id": room_id, "sequence": 0, "messages": []})


@router.get("/api/v1/chat/{room_id}")
def chat_history(room_id: str):
    with reader() as db:
        return room_messages(db, room_id)


recent_by_sender = defaultdict(deque)


@router.post("/api/v1/chat", status_code=201)
def post_chat(request: Request, body: dict = Body(...)):
    # Guests never reach this point: the boundary middleware answers 403 GUEST_WRITE_FORBIDDEN.
    with reader() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
    obj(body)
    room, text = body.get("chat_room_id"), body.get("text")
    check(isinstance(room, str) and room, "chat_room_id")
    check(isinstance(text, str) and 1 <= len(text) <= 500, "text")
    window, moment = recent_by_sender[user["user_id"]], time.monotonic()
    while window and window[0] < moment - 2:
        window.popleft()
    if len(window) >= 3 or moderation.is_flood(text):
        fail(429, "RATE_LIMITED", reason="rate_limit_exceeded" if len(window) >= 3 else "flood_character_repeat")
    window.append(moment)
    masked, flagged = moderation.mask(text)
    with transaction() as db:
        chat = room_messages(db, room)
        chat["sequence"] += 1
        message = {"sequence": chat["sequence"], "message_id": f"{room}-{chat['sequence']}", "sender": user["name"],
                   "text": masked, "flagged": flagged, "reason": "profanity_leetspeak" if flagged else None, "sent_at": now()}
        chat["messages"] = (chat["messages"] + [message])[-GUEST_ROOM_HISTORY:]
        db.put("chat", room, chat)
    live.bump("chat:" + room)
    return message


# ---------- WebSockets (NFR-1) ----------

async def push_changes(websocket, key, load):
    """Send a snapshot now and again whenever live.versions[key] moves."""
    await websocket.accept()
    live.connections += 1
    try:
        seen = None
        while True:
            version = live.versions[key]
            if version != seen:
                seen = version
                await websocket.send_json(await asyncio.to_thread(load))
            await asyncio.sleep(0.1)
    except (WebSocketDisconnect, RuntimeError, DomainError):
        pass
    finally:
        live.connections -= 1


@router.websocket("/ws/matches/{match_id}")
async def match_socket(websocket: WebSocket, match_id: str):
    def load():
        with reader() as db:
            return {"type": "score", **public_match(db, match_id)}
    await push_changes(websocket, "match:" + match_id, load)


@router.websocket("/ws/chat/{room_id}")
async def chat_socket(websocket: WebSocket, room_id: str):
    def load():
        with reader() as db:
            return {"type": "chat", **room_messages(db, room_id)}
    await push_changes(websocket, "chat:" + room_id, load)
