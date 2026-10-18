import json
import os
import urllib.request


def pytest_sessionstart(session):
    # Reset once (on the xdist controller) so match-m1 is live at 1 - 0.
    if not hasattr(session.config, "workerinput"):
        api = os.environ.get("API_URL", "http://localhost:8080")
        urllib.request.urlopen(urllib.request.Request(api + "/audit/v1/reset", data=b"{}", headers={"Content-Type": "application/json"}, method="POST"), timeout=10)
