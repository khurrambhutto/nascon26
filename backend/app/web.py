"""Guest Web Player (/web/*): zero-install, zero-login viewer opened from QR scans.

One accessible HTML page serves every route; the browser loads live data over
WebSockets and talks to the same API as the app. Guest writes are refused by the
server (403) and the page reacts by opening the progressive sign-in modal.
"""
import html
import json
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

from . import qr_guest
from .storage import reader

router = APIRouter()
PAGE = (Path(__file__).parent / "guest.html").read_text()


def render(title, context):
    page = PAGE.replace("{{TITLE}}", html.escape(title)).replace("{{CONTEXT}}", json.dumps(context))
    return HTMLResponse(page)


@router.get("/web/matches/{match_id}/{view}", response_class=HTMLResponse)
def match_page(match_id: str, view: str, state: str | None = None):
    return render("Match " + match_id, {"kind": "match", "id": match_id, "view": view, "state": state, "room": "chat-" + match_id})


@router.get("/web/costream/{session_id}", response_class=HTMLResponse)
def costream_page(session_id: str):
    return render("Co-stream " + session_id, {"kind": "costream", "id": session_id, "view": "live", "room": "chat-" + session_id})


@router.get("/web/players/{player_id}", response_class=HTMLResponse)
def player_page(player_id: str):
    return render("Player " + player_id, {"kind": "player", "id": player_id, "view": "profile", "room": "chat-" + player_id})


@router.get("/web/clubs/{club_id}", response_class=HTMLResponse)
def club_page(club_id: str):
    return render("Club " + club_id, {"kind": "club", "id": club_id, "view": "profile", "room": "chat-" + club_id})


@router.get("/web/qr", response_class=HTMLResponse)
def qr_posters():
    """Printable dynamic QR posters: one card per registered entity, with live target and scan count."""
    with reader() as db:
        cards = [qr_guest.resolve(db, record["entity_id"], inspect=True) for record in db.all("qr")]
    items = "".join(
        f'''<article class="card"><h2>{html.escape(c["entity_id"])}</h2>
        <img src="/audit/v1/qr/{c["entity_id"]}/export.svg" alt="QR code for {html.escape(c["entity_id"])}" width="220" height="220">
        <p><a href="/q/{c["short_url"].rsplit("/", 1)[1]}">{html.escape(c["short_url"])}</a></p>
        <p>Now opens: <code>{html.escape(c["target_route"])}</code> · status {html.escape(c["entity_status"])} · scans {c["scan_count"]}</p>
        <p class="row"><a class="btn" href="{c["export_formats"]["svg"]}">SVG</a><a class="btn" href="{c["export_formats"]["png"]}">PNG</a>
        <a class="btn" href="{c["export_formats"]["pdf"]}">PDF poster</a></p></article>''' for c in cards)
    page = f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ArenaOne | QR posters</title><style>
body{{margin:0;font:16px/1.5 system-ui,sans-serif;background:#fffaf6;color:#1b1b1b}} header{{background:#9c3a2a;color:#fff;padding:12px 16px}}
main{{display:grid;gap:16px;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));padding:16px}}
.card{{background:#fff;border:1px solid #d9cfc8;border-radius:12px;padding:16px}} h2{{margin:0 0 8px}} code{{word-break:break-all}}
.row{{display:flex;gap:8px;flex-wrap:wrap}} .btn{{display:inline-flex;align-items:center;min-height:48px;padding:0 16px;border-radius:8px;background:#9c3a2a;color:#fff;text-decoration:none}}
</style></head><body><header><h1 style="margin:0;font-size:1.25rem">Dynamic QR posters</h1></header><main>{items}</main></body></html>'''
    return HTMLResponse(page)
