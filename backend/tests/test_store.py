import base64
from io import BytesIO

import pytest


def order(**updates):
    return {"order_id": "ord-1", "customer_id": "cust-1", "items": [{"variant_id": "var-jersey-001-M", "quantity": 1}], "discount_code": None, "payment": {"token": "tok_success"}, **updates}


def test_receipt_totals_discount_and_retry(client):
    body = order(discount_code="WELCOME10")
    response = client.post("/audit/v1/checkout", json=body)
    assert response.status_code == 201
    value = response.json()
    assert value["total_minor"] == 405000
    assert value["discount_minor"] == 45000
    assert client.post("/audit/v1/checkout", json=body).json() == value
    assert client.post("/audit/v1/checkout", json=order(customer_id="another")).json()["error"]["code"] == "DUPLICATE_ORDER_ID"
    from pypdf import PdfReader
    text = PdfReader(BytesIO(base64.b64decode(value["receipt"]["pdf_base64"]))).pages[0].extract_text()
    assert "ord-1" in text and "PKR 4050.00" in text


@pytest.mark.parametrize("token,code", [("tok_declined", "PAYMENT_DECLINED"), ("tok_capture_fail", "PAYMENT_CAPTURE_FAILED")])
def test_payment_failure_releases_inventory(client, token, code):
    response = client.post("/audit/v1/checkout", json=order(payment={"token": token}))
    assert response.status_code == 402 and response.json()["error"]["code"] == code
    assert client.get("/audit/v1/inventory/var-jersey-001-M").json()["stock"] == 1
    assert client.post("/audit/v1/checkout", json=order()).status_code == 201


def test_multi_item_atomic_failure(client):
    body = order(items=[{"variant_id": "var-jersey-001-L", "quantity": 1}, {"variant_id": "var-jersey-001-M", "quantity": 2}])
    assert client.post("/audit/v1/checkout", json=body).status_code == 409
    assert client.get("/audit/v1/inventory/var-jersey-001-L").json()["stock"] == 5


@pytest.mark.parametrize("updates,status,code", [({"order_id": "bad|id"}, 400, "VALIDATION_ERROR"), ({"items": []}, 400, "VALIDATION_ERROR"), ({"items": [{"variant_id": "missing", "quantity": 1}]}, 404, "VARIANT_NOT_FOUND"), ({"discount_code": "BAD"}, 400, "INVALID_DISCOUNT_CODE"), ({"items": [{"variant_id": "var-jersey-001-M", "quantity": True}]}, 400, "VALIDATION_ERROR"), ({"payment": {"token": "unknown"}}, 400, "VALIDATION_ERROR")])
def test_checkout_errors(client, updates, status, code):
    response = client.post("/audit/v1/checkout", json=order(**updates))
    assert response.status_code == status
    assert response.json()["error"]["code"] == code


def test_unknown_inventory(client):
    assert client.get("/audit/v1/inventory/missing").status_code == 404
