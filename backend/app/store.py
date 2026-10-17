"""Atomic inventory checkout, mock gateway and signed PDF receipts."""
import base64
from io import BytesIO
from uuid import uuid4

from reportlab.pdfgen import canvas

from .common import check, fail, identifier, integer, now, obj, sign, text


def checkout(store, body):
    obj(body)
    oid = identifier(body.get("order_id"), "order_id")
    # Customer names/identifiers are plain data, never SQL or HTML.
    customer = text(body.get("customer_id"), "customer_id", 256)
    items = body.get("items")
    check(isinstance(items, list) and 1 <= len(items) <= 10, "items")
    ids = set()
    normalized = []
    for item in items:
        obj(item, "items")
        vid = identifier(item.get("variant_id"), "variant_id")
        quantity = integer(item.get("quantity"), "quantity", 1, 10)
        check(vid not in ids, "items")
        ids.add(vid)
        normalized.append({"variant_id": vid, "quantity": quantity})
    payment = obj(body.get("payment"), "payment")
    token = payment.get("token")
    check(token in ("tok_success", "tok_declined", "tok_capture_fail"), "payment.token")
    discount = body.get("discount_code")
    check(discount is None or isinstance(discount, str), "discount_code")
    request = {"order_id": oid, "customer_id": customer, "items": normalized, "discount_code": discount, "payment": {"token": token}}
    previous = store.get("order", oid)
    if previous:
        if previous["request"] != request:
            fail(409, "DUPLICATE_ORDER_ID")
        return previous["response"], False
    variants = {item["variant_id"]: store.require("variant", item["variant_id"]) for item in normalized}
    if discount is not None and discount != "WELCOME10":
        fail(400, "INVALID_DISCOUNT_CODE")
    for item in normalized:
        if variants[item["variant_id"]]["stock"] < item["quantity"]:
            fail(409, "OUT_OF_STOCK", variant_id=item["variant_id"])
    # The enclosing PostgreSQL row lock is held throughout authorization/capture.
    # A gateway rejection raises before any committed inventory change.
    if token == "tok_declined":
        fail(402, "PAYMENT_DECLINED")
    if token == "tok_capture_fail":
        fail(402, "PAYMENT_CAPTURE_FAILED")
    result_items = []
    for item in normalized:
        variant = variants[item["variant_id"]]
        variant["stock"] -= item["quantity"]
        store.put("variant", item["variant_id"], variant)
        result_items.append({**item, "unit_price_minor": variant["price_minor"]})
    subtotal = sum(item["quantity"] * item["unit_price_minor"] for item in result_items)
    discounted = subtotal * 10 // 100 if discount else 0
    total = subtotal - discounted
    payload = {"order_id": oid, "customer_id": customer, "items": result_items, "subtotal_minor": subtotal, "discount_minor": discounted, "total_minor": total, "currency": "PKR", "paid_at": now()}
    pdf = BytesIO()
    document = canvas.Canvas(pdf, pagesize=(420, 595))
    document.setTitle("ArenaOne receipt " + oid)
    document.setFont("Helvetica-Bold", 20)
    document.drawString(32, 550, "ArenaOne | Payment receipt")
    document.setFont("Helvetica", 11)
    lines = [f"Order: {oid}", f"Customer: {customer}", f"Paid at: {payload['paid_at']}"]
    lines += [f"{i['variant_id']} x {i['quantity']}" for i in result_items]
    lines += [f"Subtotal: PKR {subtotal // 100}.{subtotal % 100:02d}", f"Discount: PKR {discounted // 100}.{discounted % 100:02d}", f"Total: PKR {total // 100}.{total % 100:02d}"]
    for index, line in enumerate(lines):
        document.drawString(32, 515 - index * 24, line)
    document.save()
    receipt = {"payload": payload, "signature": sign(oid, payload), "pdf_base64": base64.b64encode(pdf.getvalue()).decode()}
    response = {"order_id": oid, "status": "paid", "currency": "PKR", "items": result_items, "subtotal_minor": subtotal, "discount_minor": discounted, "total_minor": total, "payment": {"authorization_id": "auth_" + uuid4().hex, "capture_id": "cap_" + uuid4().hex, "state": "captured"}, "receipt": receipt}
    store.put("order", oid, {"request": request, "response": response})
    # Transactional outbox: worker publishes to Redis Streams and archives the PDF.
    event_id = uuid4().hex
    store.put("outbox", event_id, {"event_id": event_id, "type": "order.status_changed", "order_id": oid, "status": "paid", "created_at": now(), "published": False, "archived": False})
    return response, True


def inventory(store, variant_id):
    variant = store.require("variant", variant_id)
    return {"variant_id": variant_id, "stock": variant["stock"], "reserved": 0}
