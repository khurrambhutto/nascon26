"""Guest Web Player on Selenium Grid 4 (spec 6.2.3). Run: pytest -n 3 tests/selenium/

GRID_URL       Selenium hub (default http://localhost:4444)
SELENIUM_APP_URL  backend as seen from the browser nodes (default http://backend:8080)
API_URL        backend as seen from this test process (default http://localhost:8080)
"""
import json
import os
import time
import urllib.request
import uuid

import pytest
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

GRID = os.environ.get("GRID_URL", "http://localhost:4444")
APP = os.environ.get("SELENIUM_APP_URL", "http://backend:8080")
API = os.environ.get("API_URL", "http://localhost:8080")


def by_testid(name):
    return (By.CSS_SELECTOR, f'[data-testid="{name}"]')


def api_post(path, body=None):
    request = urllib.request.Request(API + path, data=json.dumps(body or {}).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=10) as reply:
        return json.load(reply)


def browser(name):
    options = webdriver.ChromeOptions() if name == "chrome" else webdriver.FirefoxOptions()
    options.add_argument("--headless=new" if name == "chrome" else "-headless")
    return webdriver.Remote(command_executor=GRID, options=options)


@pytest.fixture
def chrome():
    driver = browser("chrome")
    yield driver
    driver.quit()


@pytest.fixture
def firefox():
    driver = browser("firefox")
    yield driver
    driver.quit()


def test_qr_resolution_and_zero_friction_load(chrome):
    started = time.monotonic()
    chrome.get(APP + "/q/m1-live")
    wait = WebDriverWait(chrome, 3)
    wait.until(EC.visibility_of_element_located(by_testid("guest-video-player")))
    wait.until(EC.visibility_of_element_located(by_testid("live-score-banner")))
    assert time.monotonic() - started < 3
    assert chrome.current_url.endswith("/web/matches/match-m1/live")
    assert not chrome.find_element(*by_testid("progressive-auth-modal")).is_displayed()


def test_guest_write_block_opens_progressive_modal(firefox):
    firefox.get(APP + "/web/matches/match-m1/live")
    wait = WebDriverWait(firefox, 5)
    field = wait.until(EC.visibility_of_element_located(by_testid("chat-input-field")))
    text = "guest says hi " + uuid.uuid4().hex[:6]
    field.send_keys(text)
    firefox.find_element(*by_testid("chat-send-btn")).click()
    wait.until(EC.visibility_of_element_located(by_testid("progressive-auth-modal")))
    assert text not in firefox.find_element(*by_testid("chat-message-list")).text


def test_guest_cart_staging_and_real_time_score(chrome):
    chrome.get(APP + "/web/matches/match-m1/live")
    wait = WebDriverWait(chrome, 5)
    wait.until(EC.visibility_of_element_located(by_testid("pinned-merch-card")))
    chrome.find_element(*by_testid("add-to-guest-cart-btn")).click()
    wait.until(EC.text_to_be_present_in_element(by_testid("guest-cart-count"), "1"))
    banner = chrome.find_element(*by_testid("live-score-banner"))
    wait.until(lambda d: " - " in banner.text)
    before = banner.text
    home = int(before.split(" - ")[0])
    api_post("/audit/v1/events", {"event_id": "sel-" + uuid.uuid4().hex, "match_id": "match-m1", "type": "score",
                                  "payload": {"team_id": "club-alpha-1", "event_type": "goal"}})
    posted = time.monotonic()
    WebDriverWait(chrome, 0.5, poll_frequency=0.02).until(lambda d: banner.text.startswith(f"{home + 1} - "))
    assert time.monotonic() - posted < 0.5
