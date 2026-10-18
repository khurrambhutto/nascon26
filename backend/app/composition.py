"""Compose a unified broadcast on the self-hosted browser grid, publishing through WHIP."""
import os
import time
import httpx
from . import costream, live, streaming
from .storage import transaction, reader

sessions = {}
seen_publishers = set()

def tick():
    if not os.environ.get('COMPOSER_GRID_URL'):
        return
    grid = os.environ['COMPOSER_GRID_URL'].rstrip('/')
    with reader() as db:
        records = db.all('costream')
    active = {r['response']['session_id'] for r in records if (r.get('state') or r['response'])['status'] == 'active'}
    for sid, browser in list(sessions.items()):
        failed = False
        try:
            title = httpx.get(grid + '/session/' + browser + '/title', timeout=1)
            failed = title.status_code != 200 or str(title.json().get('value', '')).startswith('COMPOSITE_ERROR')
        except Exception:
            failed = True
        if sid not in active or failed:
            httpx.delete(grid + '/session/' + browser, timeout=2)
            sessions.pop(sid, None)
    for sid in active - sessions.keys():
        try:
            r = httpx.post(grid + '/session', json={'capabilities': {'alwaysMatch': {'browserName': 'chrome',
                'goog:chromeOptions': {'args': ['--headless=new', '--autoplay-policy=no-user-gesture-required', '--no-sandbox']}}}}, timeout=5)
            r.raise_for_status()
            browser = r.json()['value']['sessionId']
            sessions[sid] = browser
            httpx.post(grid + '/session/' + browser + '/url', json={'url': 'http://api:8080/web/composer/' + sid}, timeout=5).raise_for_status()
        except Exception:
            sessions.pop(sid, None)


def detect_disconnects():
    if not streaming.MEDIA_API:
        return
    try:
        paths = httpx.get(streaming.MEDIA_API + '/v3/paths/list', timeout=.3).json().get('items', [])
    except Exception:
        return
    current = {p['name'] for p in paths if p.get('ready') and p['name'].startswith('live/')}
    departed = seen_publishers - current
    seen_publishers.clear()
    seen_publishers.update(current)
    if not departed:
        return
    with transaction() as db:
        players = {s['player_id'] for s in db.all('player_stream') if 'live/' + s['stream_key'] in departed}
        for record in db.all('costream'):
            sid = record['response']['session_id']
            _, state = costream.state(db, sid)
            for player in players & set(state['participants']):
                costream.depart(db, sid, player)
                live.bump('costream:' + sid)


def monitor():
    import logging
    while True:
        try:
            detect_disconnects()
        except Exception:
            logging.getLogger(__name__).exception("Media disconnect monitor")
        time.sleep(.2)
