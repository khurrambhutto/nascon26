"""Opt-in physical-phone regression for offline scoring across account switches.

Uses demo accounts and compensates only its own test scoring event after verification.
Modes: prepare-legacy (old APK), verify-legacy (updated APK), fixed (full new-APK case).
Never resets the server or clears application data. Wi-Fi is restored in finally.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import httpx

P = argparse.ArgumentParser()
P.add_argument("mode", choices=["prepare-legacy", "verify-legacy", "fixed"])
P.add_argument("--base-url", default="http://localhost:8080")
P.add_argument("--state", default="backend/.venv/account-switch-regression.json")
args = P.parse_args()
ADB = str(Path(os.getenv("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk"))) / "platform-tools/adb.exe")
PACKAGE = "com.nascon.league.local"
MATCH = "demo-m3"
client = httpx.Client(base_url=args.base_url, timeout=10)


def adb(*parts, binary=False):
    data = subprocess.check_output([ADB, *parts], timeout=45)
    return data if binary else data.decode("utf-8", errors="replace")


def tree():
    for _ in range(3):
        target = "/sdcard/arenaone-switch-" + uuid.uuid4().hex[:8] + ".xml"
        output = adb("shell", "uiautomator", "dump", target)
        if "dumped" in output:
            result = ET.fromstring(adb("shell", "cat", target))
            adb("shell", "rm", target)
            return result
    raise AssertionError("Phone UI did not become idle")


def find(tag):
    return next((n for n in tree().iter("node") if n.get("resource-id") == tag), None)


def wait_for(tag):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        node = find(tag)
        if node is not None:
            return node
        time.sleep(.3)
    raise AssertionError("Missing phone element: " + tag)


def tap(tag):
    n = wait_for(tag)
    x, y, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x+x2)//2), str((y+y2)//2))


def restart():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", PACKAGE + "/com.nascon.league.MainActivity")
    wait_for("tab_me")


def signout():
    tap("tab_me"); tap("logout_button"); tap("confirm_logout")


def login(role):
    if role == "viewer":
        tap("demo_player"); tap("email_input")
        adb("shell", "input", "keycombination", "113", "29")
        adb("shell", "input", "text", "viewer@nascon.test")
        adb("shell", "input", "keyevent", "4")
    else:
        tap("demo_organizer")
    tap("login_button")
    wait_for("tab_store" if role == "viewer" else "tab_manage")


def open_match():
    tap("tab_fixtures")
    if find("home_score") is None:
        tap("fixture_" + MATCH)
    wait_for("home_score")


def score_on_phone():
    node = wait_for("home_score")
    return next(n.get("text") for n in node.iter("node") if n.get("text", "").isdigit())


def viewer_headers():
    result = client.post("/api/v1/auth/login", json={"email": "viewer@nascon.test", "password": "Nascon@26"})
    result.raise_for_status()
    return {"Authorization": "Bearer " + result.json()["token"]}


HEADERS = viewer_headers()


def server_match():
    r = client.get("/api/v1/matches/" + MATCH, headers=HEADERS)
    r.raise_for_status()
    return r.json()


def pending_rows():
    data = adb("exec-out", "run-as", PACKAGE, "cat", "databases/league.db", binary=True)
    with tempfile.TemporaryDirectory() as folder:
        p = Path(folder) / "phone.db"
        p.write_bytes(data)
        db = sqlite3.connect(p)
        try:
            return [(a, owner, json.loads(payload)) for a, owner, payload in db.execute(
                "SELECT action_id,owner,payload FROM pending_actions WHERE type='score_event'")]
        finally:
            db.close()


def prepare():
    restart(); open_match(); signout(); login("organizer"); open_match()
    before = server_match()
    old = {r[0] for r in pending_rows()}
    adb("shell", "svc", "wifi", "disable")
    time.sleep(10)
    button = "score_club-alpha-1_goal"
    if find(button) is None:
        adb("shell", "input", "swipe", "540", "1750", "540", "1200", "350")
    tap(button)
    deadline = time.monotonic() + 8
    row = None
    while time.monotonic() < deadline:
        row = next((r for r in pending_rows() if r[0] not in old and r[2].get("match_id") == MATCH), None)
        if row is not None: break
        time.sleep(.2)
    assert row is not None, "The test goal was not persisted"
    state = {"action_id": row[0], "event_id": row[2]["event_id"], "baseline": before["home"]["score"], "match_id": MATCH}
    Path(args.state).write_text(json.dumps(state, indent=2), encoding="utf-8")
    print("Organizer goal persisted while offline", flush=True)
    signout()
    return state


def verify(state):
    started = time.monotonic()
    deadline = started + 35
    while time.monotonic() < deadline:
        updated = server_match()
        if any(e["event_id"] == state["event_id"] for e in updated["events"]):
            break
        time.sleep(.4)
    else:
        raise AssertionError("Organizer action did not upload while viewer was signed in")
    elapsed = round(time.monotonic() - started, 2)
    open_match()
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if score_on_phone() == str(updated["home"]["score"]):
            break
    else:
        raise AssertionError("Viewer score did not refresh")
    assert not any(r[0] == state["action_id"] for r in pending_rows())
    tap("tab_me")
    assert "Viewer" in ET.tostring(tree(), encoding="unicode")
    state.update({"verified": True, "server_sync_seconds": elapsed, "viewer_score": updated["home"]["score"],
                  "organizer_ui_login_required": False, "phone_queue_cleared": True})
    print(json.dumps(state), flush=True)
    # Restore the original score with a compensating event, preserving the audit history.
    token = client.post("/api/v1/auth/login", json={"email": "organizer@nascon.test", "password": "Nascon@26"}).json()["token"]
    cleanup = client.post("/api/v1/offline-sync", headers={"Authorization": "Bearer " + token}, json={
        "device_id": "qa-compensation", "actions": [{"action_id": "cleanup-"+uuid.uuid4().hex,
        "type": "score_event", "queued_at": "2026-10-18T00:00:00Z", "base_version": updated["version"],
        "payload": {"event_id": "cleanup-"+uuid.uuid4().hex, "match_id": MATCH, "type": "compensation",
        "payload": {"compensates": state["event_id"], "reason": "Remove device regression test goal"}}}]})
    cleanup.raise_for_status()
    assert cleanup.json()["results"][0]["status"] == "applied", cleanup.text
    state["test_goal_compensated"] = True
    Path(args.state).write_text(json.dumps(state, indent=2), encoding="utf-8")


try:
    if args.mode == "prepare-legacy":
        state = prepare()
        adb("shell", "svc", "wifi", "enable")
        login("viewer"); open_match()
        assert server_match()["home"]["score"] == state["baseline"]
        assert any(r[0] == state["action_id"] for r in pending_rows())
        print("Old APK reproduced: viewer online, organizer goal still queued", flush=True)
    elif args.mode == "verify-legacy":
        restart()
        verify(json.loads(Path(args.state).read_text(encoding="utf-8")))
    else:
        state = prepare()
        login("viewer")  # Cached viewer login while still offline.
        open_match()
        assert score_on_phone() == str(state["baseline"]), "Other account's private preview leaked"
        adb("shell", "svc", "wifi", "enable")
        verify(state)
finally:
    adb("shell", "svc", "wifi", "enable")
    client.close()
