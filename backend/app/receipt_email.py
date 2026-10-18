"""Durable SMTP receipt delivery. Checkout never waits for an email server."""
import base64
import os
import re
import smtplib
import ssl
import time
from email.message import EmailMessage
from uuid import uuid4

from .common import check
from .storage import transaction


def delivery_mode():
    return os.environ.get("SMTP_DELIVERY_MODE", "external")


def address(value):
    if value is None:
        return None
    check(isinstance(value, str), "receipt_email")
    value = value.strip()
    check(len(value) <= 254 and re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", value) is not None, "receipt_email")
    check(delivery_mode() == "local" or not value.lower().endswith((".test", ".invalid", ".localhost")), "receipt_email")
    return value


def configuration():
    check(delivery_mode() in ("local", "external"), "SMTP_DELIVERY_MODE")
    host = os.environ.get("SMTP_HOST", "").strip()
    sender = os.environ.get("SMTP_FROM", "").strip()
    mode = os.environ.get("SMTP_TLS_MODE", "starttls")
    if not host or not sender:
        return None
    if os.environ.get("SMTP_USERNAME") and not os.environ.get("SMTP_PASSWORD"):
        return None
    # Reject header injection and invalid settings before opening a socket.
    address(sender)
    check(mode in ("starttls", "ssl", "none"), "SMTP_TLS_MODE")
    return {"host": host, "port": int(os.environ.get("SMTP_PORT", "465" if mode == "ssl" else "587")),
        "sender": sender, "username": os.environ.get("SMTP_USERNAME", ""),
        "password": os.environ.get("SMTP_PASSWORD", ""), "mode": mode,
        "timeout": min(max(float(os.environ.get("SMTP_TIMEOUT", "10")), 1), 30)}


def enqueue(db, order_id, recipient):
    previous = db.get("receipt_email", order_id)
    if previous:
        return previous
    job = {"order_id": order_id, "recipient": recipient, "status": "queued", "attempts": 0,
        "next_attempt_at": 0, "lease_until": 0, "lease_id": None, "error": None, "sent_at": None}
    return db.put("receipt_email", order_id, job)


def public_delivery(job):
    if not job:
        return {"email": None, "status": "not_requested", "mode": delivery_mode()}
    return {"email": job["recipient"], "status": job["status"], "sent_at": job["sent_at"], "mode": delivery_mode()}


def message_for(order, job, sender):
    response = order["response"]
    message = EmailMessage()
    message["From"] = sender
    message["To"] = job["recipient"]
    message["Subject"] = "ArenaOne receipt — " + job["order_id"]
    message["X-ArenaOne-Order-ID"] = job["order_id"]
    message["Message-ID"] = "<arenaone-receipt-" + job["order_id"] + "@" + sender.split("@", 1)[1] + ">"
    amount = response["total_minor"]
    message.set_content(f"Thank you for your purchase.\n\nOrder: {job['order_id']}\nPaid: {response['currency']} {amount // 100}.{amount % 100:02d}\n\nYour PDF payment receipt is attached.\nArenaOne")
    message.add_attachment(base64.b64decode(response["receipt"]["pdf_base64"]),
        maintype="application", subtype="pdf", filename="ArenaOne-receipt-" + job["order_id"] + ".pdf")
    return message


def send(config, message):
    options = {"host": config["host"], "port": config["port"], "timeout": config["timeout"]}
    if config["mode"] == "ssl":
        connection = smtplib.SMTP_SSL(**options, context=ssl.create_default_context())
    else:
        connection = smtplib.SMTP(**options)
    with connection as smtp:
        smtp.ehlo()
        if config["mode"] == "starttls":
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
        if config["username"]:
            smtp.login(config["username"], config["password"])
        refused = smtp.send_message(message)
        if refused:
            raise smtplib.SMTPRecipientsRefused(refused)


def deliver_pending(limit=10):
    instant = time.time()
    # Claim under the shared DB lock, then release it during network delivery.
    with transaction() as db:
        jobs = [j for j in db.all("receipt_email") if j["status"] not in ("sent", "failed")
                and j["next_attempt_at"] <= instant and j["lease_until"] <= instant][:limit]
    for candidate in jobs:
        with transaction() as db:
            job = db.get("receipt_email", candidate["order_id"])
            if not job or job["status"] in ("sent", "failed") or job["lease_until"] > time.time() or job["next_attempt_at"] > time.time():
                continue
            job.update(status="sending", lease_id=uuid4().hex, lease_until=time.time() + 300)
            db.put("receipt_email", job["order_id"], job)
            order = db.require("order", job["order_id"])
        try:
            config = configuration()
            if config is None:
                job.update(status="waiting_configuration", next_attempt_at=time.time() + 60, error="SMTP_NOT_CONFIGURED")
            else:
                job["attempts"] += 1
                send(config, message_for(order, job, config["sender"]))
                job.update(status="sent", sent_at=time.time(), error=None)
        except Exception as exc:
            # Never store credentials or raw SMTP responses in public delivery status/logs.
            job.update(status="failed" if job["attempts"] >= 10 else "retrying",
                next_attempt_at=time.time() + min(3600, 30 * 2 ** min(job["attempts"], 7)), error=type(exc).__name__)
        with transaction() as db:
            current = db.get("receipt_email", job["order_id"])
            if current and current["lease_id"] == job["lease_id"]:
                job.update(lease_until=0, lease_id=None)
                db.put("receipt_email", job["order_id"], job)
