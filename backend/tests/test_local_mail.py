import base64
import smtplib
import threading

import pytest

from app import receipt_email
from app.local_smtp import SMTPHandler, SMTPServer, capture
from app.storage import transaction


@pytest.fixture
def local_mail(monkeypatch):
    monkeypatch.setenv("SMTP_DELIVERY_MODE", "local")
    monkeypatch.setenv("SMTP_HOST", "127.0.0.1")
    monkeypatch.setenv("SMTP_FROM", "receipts@arenaone.local")
    monkeypatch.setenv("SMTP_TLS_MODE", "none")
    monkeypatch.setenv("SMTP_USERNAME", "")
    monkeypatch.setenv("SMTP_PASSWORD", "")
    monkeypatch.delenv("DEMO_RECEIPT_EMAIL", raising=False)
    with SMTPServer(("127.0.0.1", 0), SMTPHandler) as server:
        monkeypatch.setenv("SMTP_PORT", str(server.server_address[1]))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        yield
        server.shutdown()
        thread.join(timeout=3)


def buy(client, login):
    response = client.post("/api/v1/checkout", headers=login("player"), json={"order_id": "local-inbox-order",
        "customer_id": "u-player", "items": [{"variant_id": "demo-variant-bottle", "quantity": 1}],
        "payment": {"token": "tok_success"}})
    assert response.status_code == 201, response.text
    return response.json()


def test_local_smtp_receipt_is_visible_and_downloadable(client, login, local_mail):
    order = buy(client, login)
    assert order["receipt_delivery"]["email"] == "player@nascon.test"
    assert order["receipt_delivery"]["mode"] == "local"
    receipt_email.deliver_pending()
    headers = login("player")
    inbox = client.get("/api/v1/mailbox", headers=headers).json()
    assert len(inbox) == 1
    assert inbox[0]["order_id"] == "local-inbox-order"
    mid = inbox[0]["message_id"]
    message = client.get("/api/v1/mailbox/" + mid, headers=headers).json()
    assert "Your PDF payment receipt is attached" in message["body"]
    pdf = client.get(f"/api/v1/mailbox/{mid}/receipt.pdf", headers=headers)
    assert pdf.headers["content-type"] == "application/pdf"
    assert pdf.content == base64.b64decode(order["receipt"]["pdf_base64"])
    assert client.get("/api/v1/orders/local-inbox-order/receipt-email", headers=headers).json()["status"] == "sent"


def test_mailbox_enforces_customer_and_admin_access(client, login, local_mail):
    buy(client, login)
    receipt_email.deliver_pending()
    assert client.get("/api/v1/mailbox").status_code == 401
    mid = client.get("/api/v1/mailbox", headers=login("player")).json()[0]["message_id"]
    assert client.get("/api/v1/mailbox", headers=login("viewer")).json() == []
    for suffix in ("", "/receipt.pdf"):
        assert client.get("/api/v1/mailbox/" + mid + suffix, headers=login("viewer")).status_code == 403
        assert client.get("/api/v1/mailbox/" + mid + suffix).status_code == 401
    assert len(client.get("/api/v1/mailbox", headers=login("admin")).json()) == 1


def test_smtp_retries_do_not_duplicate_inbox_messages(client, login, local_mail):
    buy(client, login)
    receipt_email.deliver_pending()
    with transaction() as db:
        order = db.require("order", "local-inbox-order")
        job = db.require("receipt_email", "local-inbox-order")
    # Each MIME serialization may choose another boundary, but receipt identity is stable.
    for _ in range(2):
        message = receipt_email.message_for(order, job, "receipts@arenaone.local")
        with smtplib.SMTP("127.0.0.1", int(receipt_email.configuration()["port"]), timeout=3) as smtp:
            smtp.send_message(message)
    with transaction() as db:
        assert len(db.all("local_mail")) == 1


def test_smtp_sink_rejects_unknown_or_modified_receipts(client, login, local_mail):
    buy(client, login)
    with transaction() as db:
        order = db.require("order", "local-inbox-order")
        job = db.require("receipt_email", "local-inbox-order")
    message = receipt_email.message_for(order, job, "receipts@arenaone.local")
    with pytest.raises(ValueError):
        capture(message.as_bytes(), "sender@arenaone.local", ["someone-else@nascon.test"])
    attachment = next(message.iter_attachments())
    attachment.set_payload(base64.b64encode(b"not the signed receipt").decode())
    with pytest.raises(ValueError):
        capture(message.as_bytes(), "sender@arenaone.local", [job["recipient"]])
    with transaction() as db:
        assert db.all("local_mail") == []


def test_local_inbox_html_is_available_without_external_assets(client):
    response = client.get("/local-mail")
    assert response.status_code == 200
    assert "Local inbox" in response.text and "sessionStorage" in response.text
    assert 'src="https://' not in response.text
    assert response.headers["cache-control"] == "no-store"
