"""Authenticated local inbox; each customer sees only their own receipts."""
import base64
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, Response

from . import auth
from .common import fail
from .storage import transaction

router = APIRouter()


def allowed(user, message):
    return user["role"] == "super_admin" or (
        user["user_id"] == message["customer_id"] and user["tenant_id"] == message["tenant_id"])


def message_for(db, request, mid):
    user = auth.authenticate(db, request.headers.get("authorization"))
    message = db.require("local_mail", mid)
    if not allowed(user, message):
        fail(403, "FORBIDDEN")
    return message


@router.get("/local-mail", response_class=HTMLResponse)
def inbox_page():
    return HTMLResponse(Path(__file__).with_name("mailbox.html").read_text(encoding="utf-8"),
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"})


@router.get("/api/v1/mailbox", response_model=list[dict])
def list_messages(request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        return [{k: m[k] for k in ("message_id", "order_id", "recipient", "subject", "received_at")}
                for m in sorted(db.all("local_mail"), key=lambda m: m["received_at"], reverse=True) if allowed(user, m)]


@router.get("/api/v1/mailbox/{message_id}", response_model=dict)
def read_message(message_id: str, request: Request):
    with transaction() as db:
        message = message_for(db, request, message_id)
        return {k: v for k, v in message.items() if k not in ("pdf_base64", "customer_id", "tenant_id")}


@router.get("/api/v1/mailbox/{message_id}/receipt.pdf")
def attachment(message_id: str, request: Request):
    with transaction() as db:
        message = message_for(db, request, message_id)
        return Response(base64.b64decode(message["pdf_base64"]), media_type="application/pdf",
            headers={"Content-Disposition": 'attachment; filename="ArenaOne-receipt.pdf"', "Cache-Control": "no-store"})
