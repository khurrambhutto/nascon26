"""Local inbox browser acceptance via Selenium Grid's standard WebDriver protocol.

Uses httpx already installed for backend tests; no extra browser/client downloads.
Run: backend/.venv/Scripts/python.exe tests/browser_mailbox.py
"""
import os
import time
import httpx

GRID = os.getenv("SELENIUM_URL", "http://localhost:4444")
BASE = os.getenv("BROWSER_APP_URL", "http://host.docker.internal:8080")
ELEMENT = "element-6066-11e4-a52e-4f735466cecf"


def run(browser):
    client = httpx.Client(base_url=GRID, timeout=45)
    caps = {"browserName": browser}
    if browser in ("chrome", "MicrosoftEdge"):
        caps["goog:chromeOptions" if browser == "chrome" else "ms:edgeOptions"] = {"args": ["--headless=new", "--no-sandbox"]}
    else:
        caps["moz:firefoxOptions"] = {"args": ["-headless"]}
    created = client.post("/session", json={"capabilities": {"alwaysMatch": caps}}).json()["value"]
    sid = created["sessionId"]

    def call(method, path, body=None):
        result = client.request(method, f"/session/{sid}" + path, json=body).json()["value"]
        if isinstance(result, dict) and result.get("error"):
            raise AssertionError(result["message"])
        return result

    def element(css):
        return call("POST", "/element", {"using": "css selector", "value": css})[ELEMENT]

    def wait_visible(css):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                eid = element(css)
                if call("GET", f"/element/{eid}/displayed"):
                    return eid
            except AssertionError:
                pass
            time.sleep(.2)
        raise AssertionError(f"{browser}: {css} never became visible")

    try:
        call("POST", "/url", {"url": BASE + "/local-mail"})
        for css, text in [("#email", "player@nascon.test"), ("#password", "Nascon@26")]:
            call("POST", f"/element/{element(css)}/value", {"text": text})
        call("POST", f"/element/{element('#sign-in')}/click", {})
        opened = wait_visible("#rows button")
        call("POST", f"/element/{opened}/click", {})
        wait_visible("#pdf")
        body = call("GET", f"/element/{element('#body')}/text")
        assert "receipt" in body.lower(), body
        assert "ArenaOne" in call("GET", "/title")
        call("POST", f"/element/{element('#logout')}/click", {})
        wait_visible("#sign-in")
        print(browser + ": login, receipt list, message, PDF button and logout passed")
    finally:
        client.delete(f"/session/{sid}")
        client.close()


if __name__ == "__main__":
    for name in ("chrome", "firefox", "MicrosoftEdge"):
        run(name)
