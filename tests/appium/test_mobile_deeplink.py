"""Appium 2 deep-link + cross-surface suite (spec 6.3).

Needs: Appium on :4723 with uiautomator2, a phone/emulator in `adb devices`,
and the phone able to reach the API (e.g. `adb reverse tcp:8080 tcp:8080`).
Run: pytest tests/appium --junitxml=tests/reports/appium-report.xml
"""
import json
import os
import time
import urllib.request
from pathlib import Path

import pytest
from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium import webdriver as selenium_webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait

APPIUM = os.environ.get("APPIUM_URL", "http://127.0.0.1:4723")
API = os.environ.get("API_URL", "http://localhost:8080")
GRID = os.environ.get("GRID_URL", "http://localhost:4444")
WEB = os.environ.get("SELENIUM_APP_URL", "http://backend:8080")
APK = os.environ.get("APP_PATH", str(Path(__file__).resolve().parents[2] / "mobile" / "release" / "app-release.apk"))
PACKAGE = "com.nascon.league"


def call(path, body=None, token=None):
    headers = {"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})}
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(API + path, data=data, headers=headers, method="POST" if data else "GET")
    with urllib.request.urlopen(request, timeout=10) as reply:
        return json.load(reply)


def by_tag(tag):
    return (AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().resourceId("{tag}")')


@pytest.fixture(scope="module")
def phone():
    status = json.load(urllib.request.urlopen(APPIUM + "/status", timeout=5))
    assert status["value"]["ready"] is True
    options = UiAutomator2Options().load_capabilities({
        "platformName": "Android", "appium:automationName": "UiAutomator2", "appium:app": APK,
        "appium:appPackage": PACKAGE, "appium:autoGrantPermissions": True, "appium:newCommandTimeout": 120,
    })
    driver = webdriver.Remote(APPIUM, options=options)
    call("/audit/v1/reset", {})
    yield driver
    driver.quit()


def open_link(driver, url):
    started = time.monotonic()
    driver.execute_script("mobile: deepLink", {"url": url, "package": PACKAGE})
    WebDriverWait(driver, 2).until(lambda d: d.find_elements(*by_tag("deeplink_screen")))
    return time.monotonic() - started


@pytest.mark.parametrize("url,entity", [("nascon://entity/match-m1", "match-m1"), ("nascon://stream/cs-session-101", "cs-session-101")])
def test_deep_link_opens_native_screen_without_login(phone, url, entity):
    assert open_link(phone, url) < 2.0
    assert phone.find_element(*by_tag("deeplink_entity_id")).text == entity
    assert not phone.find_elements(*by_tag("login_button"))


def test_cross_surface_costream_chat_sync(phone):
    call("/audit/v1/costream/session", {"session_id": "cs-session-101", "host_player_id": "player-alpha-1",
                                        "participants": ["player-alpha-1", "player-alpha-2"], "layout": "grid_2"})
    open_link(phone, "nascon://stream/cs-session-101")  # the mobile app joins the co-stream
    options = selenium_webdriver.ChromeOptions()
    options.add_argument("--headless=new")
    browser = selenium_webdriver.Remote(command_executor=GRID, options=options)
    try:
        browser.get(WEB + "/web/costream/cs-session-101")
        chat = WebDriverWait(browser, 5).until(lambda d: d.find_element(By.CSS_SELECTOR, '[data-testid="chat-message-list"]'))
        token = call("/api/v1/auth/login", {"email": "player@nascon.test", "password": "Nascon@26"})["token"]
        text = f"cross-surface {int(time.time())}"
        call("/api/v1/chat", {"chat_room_id": "chat-cs-session-101", "text": text}, token)
        sent = time.monotonic()
        WebDriverWait(browser, 3).until(lambda d: text in chat.text)
        WebDriverWait(phone, 3).until(lambda d: text in d.find_element(*by_tag("deeplink_chat")).text
                                      or d.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().textContains("{text}")'))
        assert time.monotonic() - sent < 3.0
    finally:
        browser.quit()
