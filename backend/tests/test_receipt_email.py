import base64
import smtplib
import socketserver
import threading
import time
from email import policy
from email.parser import BytesParser

import pytest

from app import receipt_email
from app.storage import transaction


@pytest.fixture(autouse=True)
def clean_mail_env(monkeypatch):
    for key in ("SMTP_HOST", "SMTP_FROM", "SMTP_PORT", "SMTP_USERNAME", "SMTP_PASSWORD", "SMTP_TLS_MODE", "SMTP_DELIVERY_MODE", "DEMO_RECEIPT_EMAIL"):
        monkeypatch.delenv(key, raising=False)


def purchase(**changes):
    return {"order_id": "mail-order", "customer_id": "u-player", "items": [{"variant_id": "demo-variant-bottle", "quantity": 1}],
        "discount_code": None, "payment": {"token": "tok_success"}, "receipt_email": "buyer@example.com", **changes}


def smtp_config(monkeypatch):
    monkeypatch.setenv("SMTP_HOST", "127.0.0.1")
    monkeypatch.setenv("SMTP_FROM", "receipts@example.org")
    monkeypatch.setenv("SMTP_TLS_MODE", "none")


def test_paid_purchase_queues_one_email_and_keeps_receipt(client, login):
    headers = login("player")
    body = purchase()
    response = client.post("/api/v1/checkout", json=body, headers=headers)
    assert response.status_code == 201, response.text
    assert response.json()["receipt_delivery"] == {"email": "buyer@example.com", "status": "queued", "sent_at": None, "mode": "external"}
    assert base64.b64decode(response.json()["receipt"]["pdf_base64"]).startswith(b"%PDF")
    assert client.post("/api/v1/checkout", json=body, headers=headers).status_code == 201
    with transaction() as db:
        assert len(db.all("receipt_email")) == 1
        assert db.require("variant", "demo-variant-bottle")["stock"] == 19
    assert client.post("/api/v1/checkout", json=body | {"receipt_email": "other@example.com"}, headers=headers).status_code == 409


@pytest.mark.parametrize("token", ["tok_declined", "tok_capture_fail"])
def test_failed_payment_never_queues_receipt_email(client, login, token):
    assert client.post("/api/v1/checkout", json=purchase(payment={"token": token}), headers=login("player")).status_code == 402
    with transaction() as db:
        assert db.all("receipt_email") == []
        assert db.require("variant", "demo-variant-bottle")["stock"] == 20


@pytest.mark.parametrize("email", ["bad", "victim@example.com\r\nBcc:other@example.com", "demo@nascon.test"])
def test_invalid_recipient_is_rejected_before_payment(client, login, email):
    assert client.post("/api/v1/checkout", json=purchase(receipt_email=email), headers=login("player")).status_code == 400
    with transaction() as db:
        assert db.all("order") == []


def test_receipt_email_permissions_and_optional_opt_out(client, login):
    body = purchase()
    assert client.post("/api/v1/checkout", json=body).status_code == 401
    assert client.post("/api/v1/checkout", json=body, headers=login("viewer")).status_code == 403
    client.post("/api/v1/checkout", json=body, headers=login("player"))
    assert client.get("/api/v1/orders/mail-order/receipt-email", headers=login("viewer")).status_code == 403
    assert client.get("/api/v1/orders/mail-order/receipt-email", headers=login("player")).status_code == 200
    assert client.get("/api/v1/orders/mail-order/receipt-email", headers=login("admin")).status_code == 200
    response = client.post("/api/v1/checkout", json=purchase(order_id="no-mail", receipt_email=""), headers=login("player"))
    assert response.status_code == 201
    assert response.json()["receipt_delivery"]["status"] == "not_requested"


def test_missing_smtp_configuration_retains_queue(client, login):
    client.post("/api/v1/checkout", json=purchase(), headers=login("player"))
    receipt_email.deliver_pending()
    with transaction() as db:
        job = db.require("receipt_email", "mail-order")
        assert job["status"] == "waiting_configuration"
        assert job["attempts"] == 0
        assert db.require("order", "mail-order")["status"] == "paid"


def test_demo_default_and_explicit_opt_out(client, login, monkeypatch):
    monkeypatch.setenv("DEMO_RECEIPT_EMAIL", "demo-buyer@example.com")
    headers = login("player")
    assert client.get("/api/v1/checkout/preferences", headers=headers).json()["default_receipt_email"] == "demo-buyer@example.com"
    body = purchase()
    del body["receipt_email"]
    assert client.post("/api/v1/checkout", json=body, headers=headers).json()["receipt_delivery"]["email"] == "demo-buyer@example.com"
    assert client.post("/api/v1/checkout", json=purchase(order_id="opt-out", receipt_email=""), headers=headers).json()["receipt_delivery"]["status"] == "not_requested"


def test_real_smtp_exchange_attaches_exact_pdf(client, login, monkeypatch):
    received = []
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            self.wfile.write(b"220 local-smtp ESMTP\r\n")
            while True:
                line = self.rfile.readline()
                if not line:
                    return
                command = line.split(b" ", 1)[0].strip().upper()
                if command in (b"EHLO", b"HELO"):
                    self.wfile.write(b"250 local-smtp\r\n")
                elif command in (b"MAIL", b"RCPT", b"RSET", b"NOOP"):
                    self.wfile.write(b"250 OK\r\n")
                elif command == b"DATA":
                    self.wfile.write(b"354 End with dot\r\n")
                    chunks = []
                    while True:
                        chunk = self.rfile.readline()
                        if chunk == b".\r\n" or not chunk:
                            break
                        chunks.append(chunk[1:] if chunk.startswith(b"..") else chunk)
                    received.append(b"".join(chunks))
                    self.wfile.write(b"250 accepted\r\n")
                elif command == b"QUIT":
                    self.wfile.write(b"221 bye\r\n")
                    return
    with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        smtp_config(monkeypatch)
        monkeypatch.setenv("SMTP_PORT", str(server.server_address[1]))
        response = client.post("/api/v1/checkout", json=purchase(), headers=login("player")).json()
        receipt_email.deliver_pending()
        receipt_email.deliver_pending()
        server.shutdown()
        thread.join(timeout=3)
    assert len(received) == 1
    message = BytesParser(policy=policy.default).parsebytes(received[0])
    assert message["To"] == "buyer@example.com"
    attachment = next(message.iter_attachments())
    assert attachment.get_content_type() == "application/pdf"
    assert attachment.get_payload(decode=True) == base64.b64decode(response["receipt"]["pdf_base64"])
    assert client.get("/api/v1/orders/mail-order/receipt-email", headers=login("player")).json()["status"] == "sent"


def test_smtp_failure_retries_without_repeating_payment(client, login, monkeypatch):
    smtp_config(monkeypatch)
    calls = []
    def transport(config, message):
        calls.append(message["Message-ID"])
        if len(calls) == 1:
            raise smtplib.SMTPServerDisconnected("temporary failure")
    monkeypatch.setattr(receipt_email, "send", transport)
    client.post("/api/v1/checkout", json=purchase(), headers=login("player"))
    receipt_email.deliver_pending()
    with transaction() as db:
        job = db.require("receipt_email", "mail-order")
        assert job["status"] == "retrying" and job["next_attempt_at"] > time.time()
        job["next_attempt_at"] = 0
        db.put("receipt_email", "mail-order", job)
    receipt_email.deliver_pending()
    with transaction() as db:
        assert db.require("receipt_email", "mail-order")["status"] == "sent"
        assert db.require("variant", "demo-variant-bottle")["stock"] == 19
    assert calls[0] == calls[1]


def test_only_one_worker_claims_a_receipt(client, login, monkeypatch):
    smtp_config(monkeypatch)
    entered, release = threading.Event(), threading.Event()
    calls = []
    def transport(config, message):
        calls.append(message)
        entered.set()
        assert release.wait(timeout=5)
    monkeypatch.setattr(receipt_email, "send", transport)
    client.post("/api/v1/checkout", json=purchase(), headers=login("player"))
    thread = threading.Thread(target=receipt_email.deliver_pending)
    thread.start()
    try:
        assert entered.wait(timeout=5)
        receipt_email.deliver_pending()
    finally:
        release.set()
        thread.join(timeout=5)
    assert len(calls) == 1


def test_starttls_happens_before_login(monkeypatch):
    smtp_config(monkeypatch)
    monkeypatch.setenv("SMTP_TLS_MODE", "starttls")
    monkeypatch.setenv("SMTP_USERNAME", "sender@example.org")
    monkeypatch.setenv("SMTP_PASSWORD", "test-app-token")
    operations = []
    class Transport:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def ehlo(self): operations.append("ehlo")
        def starttls(self, **kwargs): operations.append("starttls")
        def login(self, *args): operations.append("login")
        def send_message(self, message): operations.append("send"); return {}
    monkeypatch.setattr(smtplib, "SMTP", Transport)
    receipt_email.send(receipt_email.configuration(), object())
    assert operations == ["ehlo", "starttls", "ehlo", "login", "send"]
