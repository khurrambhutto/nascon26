def test_fulfilment_refund_and_idempotency(client, login):
    customer = login("viewer")
    manager = login("manager")
    body = {"order_id": "fulfilment-1", "customer_id": "u-viewer", "items": [{"variant_id": "var-jersey-001-M", "quantity": 1}], "payment": {"token": "tok_success"}}
    checkout = client.post("/audit/v1/checkout", json=body)
    assert checkout.status_code == 201
    assert len(client.get("/api/v1/orders", headers=customer).json()) == 1
    assert client.get("/api/v1/orders/fulfilment-1", headers=customer).status_code == 200
    path = "/api/v1/orders/fulfilment-1/status"
    assert client.patch(path, headers=customer, json={"request_id": "denied", "status": "packed"}).status_code == 403
    for state in ("packed", "shipped", "delivered", "refunded"):
        payload = {"request_id": "state-"+state, "status": state}
        response = client.patch(path, headers=manager, json=payload)
        assert response.status_code == 200, response.text
        assert response.json()["status"] == state
        assert client.patch(path, headers=manager, json=payload).json() == response.json()
    assert client.get("/audit/v1/inventory/var-jersey-001-M").json()["stock"] == 1
    assert client.patch(path, headers=manager, json={"request_id": "refund-again", "status": "refunded"}).status_code == 409
    assert client.post("/audit/v1/checkout", json=body).json() == checkout.json()
    assert client.get("/api/v1/orders/fulfilment-1", headers=login("player")).status_code == 403


def test_cancellation_restores_inventory_once(client, login):
    body = {"order_id": "cancel-1", "customer_id": "u-viewer", "items": [{"variant_id": "var-jersey-001-M", "quantity": 1}], "payment": {"token": "tok_success"}}
    assert client.post("/audit/v1/checkout", json=body).status_code == 201
    payload = {"request_id": "cancel-request", "status": "cancelled"}
    headers = login("manager")
    assert client.patch("/api/v1/orders/cancel-1/status", headers=headers, json=payload).status_code == 200
    assert client.patch("/api/v1/orders/cancel-1/status", headers=headers, json=payload).status_code == 200
    assert client.get("/audit/v1/inventory/var-jersey-001-M").json()["stock"] == 1
