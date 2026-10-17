import os
import sys
from pathlib import Path

os.environ["DATABASE_URL"] = "sqlite://"
os.environ["DAY1_SALT"] = "demo-salt"
os.environ.pop("REDIS_URL", None)
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest
from fastapi.testclient import TestClient
from app.main import app, requests_by_ip


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as client:
        yield client


@pytest.fixture(autouse=True)
def fresh(client):
    requests_by_ip.clear()
    assert client.post("/audit/v1/reset").status_code == 200


@pytest.fixture
def login(client):
    def headers(role="manager"):
        response = client.post("/api/v1/auth/login", json={"email": role + "@nascon.test", "password": "Nascon@26"})
        assert response.status_code == 200, response.text
        return {"Authorization": "Bearer " + response.json()["token"]}
    return headers
