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
