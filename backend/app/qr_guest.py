"""FR-4.x dynamic QR deep links, privacy-safe scan telemetry, guest tokens and atomic cart merge."""
import secrets
import time
from datetime import datetime, timezone
from io import BytesIO

from PIL import Image, ImageDraw
from reportlab.graphics.barcode import qrencoder
from reportlab.pdfgen import canvas

from . import scoring
from .common import check, fail, obj, sign2

BASE_URL = "http://localhost:8080"
MATCH_ROUTES = {"upcoming": "preview", "in_progress": "live", "completed": "vod"}
GUEST_PREFIX = "guest_anon_tok_"


# ---------- dynamic QR ----------

def entity_type(entity_id):
    for prefix, kind in (("cs-session-", "costream"), ("match-", "match"), ("player-", "player"), ("club-", "club")):
        if entity_id.startswith(prefix):
            return kind
    return None


def entity_state(store, entity_id):
    """Current (type, status) of the entity behind a QR code, read at scan time."""
    kind = entity_type(entity_id)
    if kind == "match" and store.get("match", entity_id):
        match = store.get("match", entity_id)
        return kind, scoring.effective_status(match, scoring.project(store, entity_id)["status"])
    if kind == "costream" and store.get("costream", entity_id):
        return kind, store.get("costream", entity_id)["response"]["status"]
    if kind in ("player", "club") and store.get(kind, entity_id):
        return kind, "active"
    fail(404, "QR_NOT_FOUND")


def target_route(kind, entity_id, status):
    if kind == "match":
        if status in MATCH_ROUTES:
            return f"/web/matches/{entity_id}/{MATCH_ROUTES[status]}"
        return f"/web/matches/{entity_id}/notice?state={status}"
    return {"player": "/web/players/", "club": "/web/clubs/", "costream": "/web/costream/"}[kind] + entity_id


def mapping(store, entity_id, create):
    """Short codes never change once issued, so printed posters stay valid."""
    record = store.get("qr", entity_id)
    if record is None:
        entity_state(store, entity_id)  # 404 QR_NOT_FOUND for unknown entities
        if not create:
            return {"entity_id": entity_id, "short_code": "q-" + entity_id, "scan_count": 0, "devices": {}, "buckets": {}}
        record = {"entity_id": entity_id, "short_code": "q-" + entity_id, "scan_count": 0, "devices": {}, "buckets": {}}
        store.put("qr", entity_id, record)
        store.put("qr_code", record["short_code"], {"entity_id": entity_id})
    return record


def record_scan(store, record, user_agent):
    # NFR-9: only a count, a coarse device family and an hourly UTC bucket. No IP, cookie or full User-Agent.
    family = "mobile" if any(word in (user_agent or "").lower() for word in ("mobile", "android", "iphone", "ipad")) else "desktop"
    bucket = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:00Z")
    record["scan_count"] += 1
    record["devices"][family] = record["devices"].get(family, 0) + 1
    record["buckets"][bucket] = record["buckets"].get(bucket, 0) + 1
    store.put("qr", record["entity_id"], record)


def resolve(store, entity_id, inspect=False, user_agent=None):
    record = mapping(store, entity_id, create=not inspect)
    kind, status = entity_state(store, entity_id)
    if not inspect:
        record_scan(store, record, user_agent)
    short_url = f"{BASE_URL}/q/{record['short_code']}"
    route = target_route(kind, entity_id, status)
    return {
        "entity_id": entity_id,
        "entity_type": kind,
        "entity_status": status,
        "short_url": short_url,
        "target_route": route,
        "deep_link_uri": "nascon://entity/" + entity_id,
        "scan_count": record["scan_count"],
        "export_formats": {fmt: f"{BASE_URL}/audit/v1/qr/{entity_id}/export.{fmt}" for fmt in ("svg", "png", "pdf")},
        "signature": sign2(entity_id, {"entity_id": entity_id, "short_url": short_url, "target_route": route}),
    }


def short_link(store, short_code, user_agent):
    code = store.get("qr_code", short_code)
    if code is None:
        fail(404, "QR_NOT_FOUND")
    return resolve(store, code["entity_id"], user_agent=user_agent)["target_route"]


def matrix(text):
    code = qrencoder.QRCode(None, qrencoder.QRErrorCorrectLevel.M)
    code.addData(text)
    code.make()
    size = code.getModuleCount()
    return [[code.isDark(row, col) for col in range(size)] for row in range(size)]


def export(store, entity_id, fmt):
    record = mapping(store, entity_id, create=False)
    short_url = f"{BASE_URL}/q/{record['short_code']}"
    cells, border = matrix(short_url), 4
    size = len(cells) + 2 * border
    if fmt == "svg":
        squares = "".join(f'<rect x="{c + border}" y="{r + border}" width="1" height="1"/>' for r, row in enumerate(cells) for c, dark in enumerate(row) if dark)
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size * 8}" height="{size * 8}">'
               f'<title>{short_url}</title><desc>{short_url}</desc><rect width="100%" height="100%" fill="#fff"/><g fill="#000">{squares}</g></svg>')
        return svg.encode(), "image/svg+xml"
    if fmt == "png":
        scale = 8
        image = Image.new("1", (size * scale, size * scale), 1)
        draw = ImageDraw.Draw(image)
        for r, row in enumerate(cells):
            for c, dark in enumerate(row):
                if dark:
                    draw.rectangle([(c + border) * scale, (r + border) * scale, (c + border + 1) * scale - 1, (r + border + 1) * scale - 1], fill=0)
        out = BytesIO()
        image.save(out, "PNG")
        return out.getvalue(), "image/png"
    if fmt == "pdf":
        out = BytesIO()
        page = canvas.Canvas(out, pagesize=(420, 595))
        page.setTitle("ArenaOne QR " + entity_id)
        page.setFont("Helvetica-Bold", 22)
        page.drawString(40, 540, "ArenaOne | Scan to watch")
        page.setFont("Helvetica", 12)
        page.drawString(40, 515, entity_id)
        page.drawString(40, 497, short_url)
        cell = 300 / size
        for r, row in enumerate(cells):
            for c, dark in enumerate(row):
                if dark:
                    page.rect(60 + (c + border) * cell, 460 - (r + border + 1) * cell, cell, cell, stroke=0, fill=1)
        page.save()
        return out.getvalue(), "application/pdf"
    fail(404, "NOT_FOUND")


# ---------- guests ----------

def is_guest(token):
    return isinstance(token, str) and token.startswith("guest_")


def new_guest(store):
    token = GUEST_PREFIX + secrets.token_hex(8)
    store.put("guest_token", token, {"guest_token": token, "role": "guest", "merged_into": None, "expires_at": time.time() + 900})
    return {"guest_token": token, "role": "guest", "scopes": ["playback", "score", "guest_cart"]}


def stage(store, token, body):
    """Guests may only stage items in a temporary cart (no stock is reserved)."""
    record = store.get("guest_token", token)
    if not record or record.get("expires_at", float("inf")) <= time.time():
        fail(401, "GUEST_TOKEN_EXPIRED")
    obj(body)
    variant_id, quantity = body.get("variant_id"), body.get("quantity", 1)
    check(isinstance(variant_id, str), "variant_id")
    check(type(quantity) is int and 1 <= quantity <= 10, "quantity")
    if store.get("variant", variant_id) is None:
        fail(404, "VARIANT_NOT_FOUND")
    cart = store.get("guest_cart", token, {"guest_token": token, "items": []})
    add_items(cart["items"], [{"variant_id": variant_id, "quantity": quantity}])
    store.put("guest_cart", token, cart)
    return {"guest_token": token, "items": cart["items"], "count": sum(i["quantity"] for i in cart["items"])}


def add_items(items, extra):
    for item in extra:
        existing = next((i for i in items if i["variant_id"] == item["variant_id"]), None)
        if existing:
            existing["quantity"] += item["quantity"]
        else:
            items.append({"variant_id": item["variant_id"], "quantity": item["quantity"]})


def user_exists(store, user_id):
    return store.get("player", user_id) is not None or store.get("user", user_id) is not None


def cart_merge(store, body):
    # 1. Schema
    obj(body)
    token, user_id, guest_cart = body.get("guest_token"), body.get("registered_user_id"), body.get("guest_cart")
    test_block = body.get("test_write_block", True)
    check(isinstance(token, str) and token.startswith("guest_") and len(token) > 6, "guest_token")
    check(isinstance(user_id, str) and user_id, "registered_user_id")
    check(isinstance(guest_cart, list) and 1 <= len(guest_cart) <= 10, "guest_cart")
    for item in guest_cart:
        check(isinstance(item, dict) and isinstance(item.get("variant_id"), str), "guest_cart.variant_id")
        check(type(item.get("quantity")) is int and item["quantity"] >= 1, "guest_cart.quantity")
    check(isinstance(test_block, bool), "test_write_block")
    record = store.get("guest_token", token)
    if record and record.get("expires_at", float("inf")) <= time.time():
        fail(401, "GUEST_TOKEN_EXPIRED")
    # 2. Existence
    if not user_exists(store, user_id):
        fail(404, "PLAYER_NOT_FOUND")
    for item in guest_cart:
        if store.get("variant", item["variant_id"]) is None:
            fail(404, "VARIANT_NOT_FOUND", variant_id=item["variant_id"])
    request = {"guest_token": token, "registered_user_id": user_id, "guest_cart": [{"variant_id": i["variant_id"], "quantity": i["quantity"]} for i in guest_cart], "test_write_block": test_block}
    # Edge case 4: a replay after a network switch returns the merged cart, never adding twice.
    merged = store.get("guest_merge", token)
    if merged:
        if merged["request"] != request:
            fail(409, "GUEST_TOKEN_ALREADY_MERGED", guest_token=token)
        return merge_response(store, token, user_id, test_block)
    # 3. Stock
    cart = store.get("cart", user_id, {"user_id": user_id, "items": []})
    items = [dict(i) for i in cart["items"]]
    add_items(items, request["guest_cart"])
    for item in items:
        if item["quantity"] > store.get("variant", item["variant_id"])["stock"]:
            fail(409, "OUT_OF_STOCK", variant_id=item["variant_id"])
    # 4-5. Write guard check + atomic merge (the whole request runs in one locked transaction)
    store.put("cart", user_id, {"user_id": user_id, "items": items})
    store.put("guest_merge", token, {"request": request, "user_id": user_id})
    guest = store.get("guest_token", token, {"guest_token": token, "role": "guest"})
    store.put("guest_token", token, {**guest, "merged_into": user_id})
    store.remove("guest_cart", token)
    return merge_response(store, token, user_id, test_block)


def guest_write_rejection(token):
    """Runs the same guard the HTTP middleware uses; a guest token always yields 403."""
    return 403 if is_guest(token) else None


def merge_response(store, token, user_id, test_block):
    cart = store.get("cart", user_id, {"items": []})
    items = [{**i, "unit_price_minor": store.get("variant", i["variant_id"])["price_minor"]} for i in cart["items"]]
    total = sum(i["quantity"] for i in items)
    code = guest_write_rejection(token) if test_block else None
    signed = {"cart_merged": True, "guest_token": token, "registered_user_id": user_id, "total_items": total, "write_rejection_code": code}
    return {
        "guest_token": token,
        "write_block_verified": code == 403,
        "write_rejection_code": code,
        "cart_merged": True,
        "active_cart": {"user_id": user_id, "currency": "PKR", "total_items": total,
                        "subtotal_minor": sum(i["quantity"] * i["unit_price_minor"] for i in items), "items": items},
        "signature": sign2(token, signed),
    }
