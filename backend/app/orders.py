"""Order fulfilment and mock refunds with idempotent stock restoration."""
from uuid import uuid4

from .common import check, fail, identifier, now, obj

TRANSITIONS = {
    "pending": {"paid", "cancelled"},
    "paid": {"packed", "cancelled", "refunded"},
    "packed": {"shipped", "cancelled", "refunded"},
    "shipped": {"delivered", "refunded"},
    "delivered": {"refunded"},
    "cancelled": set(),
    "refunded": set(),
}


def visible_order(user, order):
    return user["role"] == "super_admin" or (
        user["tenant_id"] == order.get("tenant_id", "tenant-alpha") and (
            order["request"]["customer_id"] == user["user_id"] or
            (user["role"] == "club_manager" and order.get("club_ids") == [user["club_id"]])
        )
    )


def public_order(order):
    return {**order["response"], "status": order.get("status", "paid"), "history": order.get("history", [])}


def transition(store, order_id, body, user):
    obj(body)
    rid = identifier(body.get("request_id"), "request_id")
    status = body.get("status")
    check(isinstance(status, str) and status in TRANSITIONS, "status")
    order = store.require("order", order_id)
    can_manage = user["role"] == "super_admin" or (user["role"] == "club_manager" and visible_order(user, order) and order.get("club_ids") == [user["club_id"]])
    if not can_manage:
        fail(403, "FORBIDDEN")
    request = {"order_id": order_id, "status": status, "requested_by": user["user_id"]}
    previous = store.get("order_transition", rid)
    if previous:
        if previous["request"] != request:
            fail(409, "DUPLICATE_REQUEST_ID")
        return previous["response"]
    current = order.get("status", "paid")
    if status not in TRANSITIONS[current]:
        fail(409, "ILLEGAL_TRANSITION")
    if status in ("cancelled", "refunded") and current != "pending":
        # Mock refund succeeds locally. A terminal state prevents a second return.
        for item in order["request"]["items"]:
            variant = store.require("variant", item["variant_id"])
            variant["stock"] += item["quantity"]
            store.put("variant", item["variant_id"], variant)
        order["refund"] = {"refund_id": "refund_" + uuid4().hex, "state": "refunded", "amount_minor": order["response"]["total_minor"]}
    event = {"event_id": uuid4().hex, "type": "order.status_changed", "order_id": order_id, "status": status, "created_at": now(), "published": False, "archived": False}
    order["status"] = status
    order.setdefault("history", []).append({"status": status, "who": user["user_id"], "when": event["created_at"]})
    store.put("order", order_id, order)
    store.put("outbox", event["event_id"], event)
    response = public_order(order)
    if order.get("refund"):
        response["refund"] = order["refund"]
    store.put("order_transition", rid, {"request": request, "response": response})
    return response
