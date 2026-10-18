"""End-to-end local SMTP receipt probe. Refuses to run in external delivery mode."""
import base64
import json
import sys
import time
from uuid import uuid4
import httpx


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    recipient = "mohammadmohid93@gmail.com"
    with httpx.Client(base_url=base, timeout=10) as client:
        login = client.post("/api/v1/auth/login", json={"email": "player@nascon.test", "password": "Nascon@26"})
        login.raise_for_status()
        headers = {"Authorization": "Bearer " + login.json()["token"]}
        prefs = client.get("/api/v1/checkout/preferences", headers=headers).json()
        assert prefs["delivery_mode"] == "local", "This probe must not send external email"
        oid = "smtp-retest-" + uuid4().hex[:12]
        result = client.post("/api/v1/checkout", headers=headers, json={"order_id": oid,
            "customer_id": login.json()["user"]["user_id"], "items": [{"variant_id": "demo-variant-bottle", "quantity": 1}],
            "payment": {"token": "tok_success"}, "receipt_email": recipient})
        result.raise_for_status()
        expected = base64.b64decode(result.json()["receipt"]["pdf_base64"])
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status = client.get(f"/api/v1/orders/{oid}/receipt-email", headers=headers).json()
            if status["status"] == "sent":
                break
            time.sleep(.5)
        assert status["status"] == "sent", status
        messages = client.get("/api/v1/mailbox", headers=headers).json()
        message = next(m for m in messages if m["order_id"] == oid)
        assert message["recipient"] == recipient
        pdf = client.get(f"/api/v1/mailbox/{message['message_id']}/receipt.pdf", headers=headers)
        assert pdf.status_code == 200 and pdf.content == expected
        print(json.dumps({"order_id": oid, "recipient": recipient, "smtp_capture": "passed",
            "mode": status["mode"], "attachment_matches_checkout": True, "pdf_bytes": len(pdf.content),
            "external_email_sent": False}), flush=True)


if __name__ == "__main__":
    main()
