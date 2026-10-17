"""Verify a live SSE connection receives a newly committed scoring event."""
import json
import queue
import sys
import threading
from urllib.request import Request, urlopen
from uuid import uuid4

from smoke_backend import BASE, request


def main():
    status, account = request("POST", "/api/v1/auth/login", {"email": "organizer@nascon.test", "password": "Nascon@26"})
    assert status == 200
    messages = queue.Queue()

    def stream():
        try:
            req = Request(BASE + "/api/v1/matches/demo-m3/stream", headers={"Authorization": "Bearer " + account["token"]})
            with urlopen(req, timeout=8) as response:
                assert response.headers.get_content_type() == "text/event-stream"
                count = 0
                for raw in response:
                    line = raw.decode().strip()
                    if line.startswith("data: "):
                        messages.put(json.loads(line[6:]))
                        count += 1
                        if count == 2:
                            return
        except Exception as exc:
            messages.put(exc)

    thread = threading.Thread(target=stream, daemon=True)
    thread.start()
    before = messages.get(timeout=8)
    assert isinstance(before, dict), before
    status, result = request("POST", "/audit/v1/events", {"event_id": "stream-" + uuid4().hex, "match_id": "demo-m3", "type": "score", "payload": {"team_id": "club-alpha-1", "event_type": "goal"}})
    assert status == 201, result
    after = messages.get(timeout=8)
    assert isinstance(after, dict), after
    assert after["version"] == before["version"] + 1
    assert after["home"]["score"] == before["home"]["score"] + 1
    thread.join(timeout=2)
    print("SSE emitted the updated score and event timeline after commit.")


if __name__ == "__main__":
    main()
