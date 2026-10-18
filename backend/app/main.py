"""Audit contract and authenticated mobile API over shared domain services."""
import asyncio
import json
import logging
import os
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from threading import Lock
from uuid import uuid4

import redis
from fastapi import Body, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.exceptions import HTTPException

from . import auth, fixtures, offline, orders, roster, scheduling, scoring, seed, standings, store as merchandise
from .common import DomainError, canonical, check, fail, identifier, obj
from .storage import initialize, transaction
from .schemas import (CheckoutRequest, EventRequest, FixturesRequest, LeagueRequest,
                      LeagueConfigurationRequest, LoginRequest, OfflineRequest,
                      OrderStatusRequest, PinRequest, ResetRequest, RosterSetupRequest,
                      SportRequest, TenantBrandingRequest, TransferRequest)

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app):
    initialize()
    with transaction() as db:
        if not db.get("meta", "seeded"):
            seed.reset(db)
        from .demo_catalog import ensure
        ensure(db)
    yield


app = FastAPI(title="ArenaOne / NaSCon Part I", version="1.0.0", lifespan=lifespan)
from .management import router as management_router
app.include_router(management_router)
from .mailbox import router as mailbox_router
app.include_router(mailbox_router)
from .day2 import router as day2_router
app.include_router(day2_router)
from .web import router as web_router
app.include_router(web_router)
from . import live
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
requests_by_ip = defaultdict(deque)
rate_lock = Lock()
cache = redis.Redis.from_url(os.environ["REDIS_URL"], socket_connect_timeout=1, socket_timeout=1) if os.environ.get("REDIS_URL") else None
RATE_SCRIPT = """
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - 10000)
if redis.call('ZCARD', KEYS[1]) >= 100 then return 0 end
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], 11000)
return 1
"""


@app.middleware("http")
async def boundaries(request, call_next):
    # Buffer at most 1 MB even when Content-Length is absent or incorrect.
    chunks, length = [], 0
    async for chunk in request.stream():
        length += len(chunk)
        if length > 1024 * 1024:
            return JSONResponse(DomainError(413, "PAYLOAD_TOO_LARGE").body, status_code=413)
        chunks.append(chunk)
    request._body = b"".join(chunks)
    # FR-4.3: guests (guest_* bearer, or no credentials on chat) can never write. Enforced server-side.
    path, header = request.url.path, request.headers.get("authorization", "")
    if request.method in ("POST", "PUT", "PATCH", "DELETE") and not path.startswith(("/api/v1/guest/", "/audit/v1/guest/")):
        if header.startswith("Bearer guest_") or (path == "/api/v1/chat" and not header):
            token = header[7:] or None
            return JSONResponse(DomainError(403, "GUEST_WRITE_FORBIDDEN", "Anonymous guest tokens cannot perform write mutations.", guest_token=token).body, status_code=403)
    if request._body and request.method in ("POST", "PUT", "PATCH") and "/rbac/" not in request.url.path:
        def reject_constant(value):
            raise ValueError("Non-finite JSON number")
        try:
            json.loads(request._body.decode("utf-8"), parse_constant=reject_constant)
        except (ValueError, UnicodeDecodeError):
            return JSONResponse(DomainError(400, "VALIDATION_ERROR", fields=["body"]).body, status_code=400)
    if request.url.path.startswith("/api/v1/"):
        ip, allowed = request.client.host if request.client else "unknown", None
        if cache is not None:
            try:
                allowed = bool(cache.eval(RATE_SCRIPT, 1, "rate:" + ip, int(time.time()*1000), uuid4().hex))
            except redis.RedisError:
                log.warning("Redis unavailable; using local rate limiter")
        if allowed is None:
            with rate_lock:
                current = time.monotonic()
                queue = requests_by_ip[ip]
                while queue and queue[0] <= current - 10:
                    queue.popleft()
                allowed = len(queue) < 100
                if allowed:
                    queue.append(current)
        if not allowed:
            return JSONResponse(DomainError(429, "RATE_LIMITED").body, status_code=429, headers={"Retry-After": "10"})
    return await call_next(request)


@app.exception_handler(DomainError)
async def domain_error(request, exc):
    return JSONResponse(exc.body, status_code=exc.status)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    fields = list(dict.fromkeys(".".join(str(x) for x in e["loc"] if x != "body") or "body" for e in exc.errors()))
    return JSONResponse(DomainError(400, "VALIDATION_ERROR", fields=fields).body, status_code=400)


@app.exception_handler(HTTPException)
async def http_error(request, exc):
    return JSONResponse(DomainError(exc.status_code, "NOT_FOUND" if exc.status_code == 404 else "HTTP_ERROR").body, status_code=exc.status_code)


@app.exception_handler(Exception)
async def unexpected_error(request, exc):
    log.exception("Unhandled request error", exc_info=exc)
    return JSONResponse(DomainError(500, "INTERNAL_ERROR", "The server could not complete this request.").body, status_code=500)


def action_result(result):
    response, created = result
    return JSONResponse(response, status_code=201 if created else 200)


@app.get("/health", response_model=dict)
def health():
    with transaction() as db:
        if not db.get("meta", "seeded"):
            fail(503, "NOT_READY")
    return {"status": "ok"}


@app.post("/audit/v1/reset", response_model=dict)
def reset(body: ResetRequest | None = Body(default=None)):
    with transaction() as db:
        result = seed.reset(db, body.data() if body else None)
    for key in list(live.versions):
        live.bump(key)
    return result


@app.post("/audit/v1/sports", response_model=dict, status_code=201)
def sports(body: SportRequest):
    with transaction() as db:
        return scoring.create_sport(db, body.data())


@app.post("/audit/v1/fixtures", response_model=dict)
def fixture_generation(body: FixturesRequest):
    return fixtures.generate(body.data())


@app.post("/audit/v1/leagues", response_model=dict, status_code=201)
def leagues(body: LeagueRequest):
    with transaction() as db:
        return scoring.register_league(db, body.data())


@app.post("/audit/v1/events", response_model=dict)
def events(body: EventRequest):
    with transaction() as db:
        result = scoring.append_event(db, body.data())
    live.bump("match:" + body.match_id)
    return action_result(result)


@app.get("/audit/v1/score/{match_id}", response_model=dict)
def score(match_id: str):
    with transaction() as db:
        return scoring.project(db, match_id)


@app.get("/audit/v1/standings/{league_id}", response_model=dict)
def table(league_id: str):
    with transaction() as db:
        return standings.standings(db, league_id)


@app.post("/audit/v1/roster/setup", response_model=dict, status_code=201)
def roster_setup(body: RosterSetupRequest):
    with transaction() as db:
        return roster.setup(db, body.data())


@app.post("/audit/v1/roster/transfer", response_model=dict)
def roster_transfer(body: TransferRequest):
    with transaction() as db:
        return action_result(roster.transfer(db, body.data()))


@app.get("/audit/v1/roster/history/{player_id}", response_model=dict)
def roster_history(player_id: str, as_of: str | None = None):
    with transaction() as db:
        return roster.history(db, player_id, as_of)


@app.post("/audit/v1/checkout", response_model=dict)
def checkout(body: CheckoutRequest):
    with transaction() as db:
        return action_result(merchandise.checkout(db, body.data()))


@app.get("/audit/v1/inventory/{variant_id}", response_model=dict)
def inventory(variant_id: str):
    with transaction() as db:
        return merchandise.inventory(db, variant_id)


@app.post("/audit/v1/offline-sync", response_model=dict)
def offline_sync(body: OfflineRequest):
    with transaction() as db:
        return offline.sync(db, body.data())


@app.post("/audit/v1/rbac-matrix", response_model=dict)
def rbac_matrix():
    with transaction() as db:
        return auth.matrix(db)


def probe(request, action, target=None):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        auth.authorize(db, user, action, target)
    return {"authorized": True}


@app.post("/audit/v1/rbac/tenants", response_model=dict)
def probe_tenants(request: Request):
    return probe(request, "tenants")


@app.post("/audit/v1/rbac/leagues/{league_id}/fixtures", response_model=dict)
def probe_fixtures(league_id: str, request: Request):
    return probe(request, "fixtures", league_id)


@app.post("/audit/v1/rbac/clubs/{club_id}/roster", response_model=dict)
def probe_roster(club_id: str, request: Request):
    return probe(request, "roster", club_id)


@app.post("/audit/v1/rbac/clubs/{club_id}/products", response_model=dict)
def probe_products(club_id: str, request: Request):
    return probe(request, "products", club_id)


@app.patch("/audit/v1/rbac/players/{player_id}", response_model=dict)
def probe_player(player_id: str, request: Request):
    return probe(request, "player", player_id)


@app.get("/audit/v1/rbac/clubs/{club_id}", response_model=dict)
def probe_club(club_id: str, request: Request):
    return probe(request, "club", club_id)


@app.get("/audit/v1/rbac/platform/config", response_model=dict)
def probe_config(request: Request):
    return probe(request, "config")


@app.get("/api/v1/ping", response_model=dict)
def ping():
    return {"pong": True}


@app.post("/api/v1/auth/login", response_model=dict)
def login(body: LoginRequest):
    with transaction() as db:
        return auth.login(db, body.data())


@app.get("/api/v1/me", response_model=dict)
def me(request: Request):
    with transaction() as db:
        return auth.identity(db, auth.authenticate(db, request.headers.get("authorization")))


@app.get("/api/v1/leagues", response_model=list[dict])
def app_leagues(request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        return [{k: league[k] for k in ("league_id", "name", "sport_id", "tenant_id")} for league in db.all("league") if auth.visible(user, league["tenant_id"])]


def product_json(db, product):
    return {k: product[k] for k in ("product_id", "name", "category", "description")} | {"variants": [db.require("variant", vid) for vid in product["variant_ids"]]}


def app_match(db, match_id, user):
    match = db.require("match", match_id)
    auth.tenant_access(user, match["tenant_id"])
    product = db.get("product", match.get("pinned_product_id", ""))
    result = {**scoring.project(db, match_id), "pinned_product": product_json(db, product) if product else None,
              "scheduled_at": match.get("scheduled_at"), "venue": match.get("venue")}
    if match.get("operational_status"):
        result["status"] = match["operational_status"]
    return result


@app.get("/api/v1/fixtures", response_model=list[dict])
def app_fixtures(request: Request, league_id: str = "league-alpha-1"):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        league = db.require("league", league_id)
        auth.tenant_access(user, league["tenant_id"])
        result = []
        for fixture in league["fixtures"]:
            match = db.require("match", fixture["match_id"])
            projection = scoring.project(db, match["match_id"])
            home = db.get("club", match["home_team_id"], {})
            away = db.get("club", match["away_team_id"], {})
            result.append({"match_id": match["match_id"], "league_id": league_id, "sport_id": match["sport_id"], "home_team_id": match["home_team_id"], "home_name": home.get("name", match["home_team_id"]), "away_team_id": match["away_team_id"], "away_name": away.get("name", match["away_team_id"]), "status": match.get("operational_status") or projection["status"], "scheduled_at": match.get("scheduled_at"), "venue": match.get("venue"), "home_score": projection["home"]["score"], "away_score": projection["away"]["score"]})
        return result


@app.get("/api/v1/fixtures/highlights", response_model=list[dict])
def sport_highlights(request: Request):
    """One actual fixture per configured sport, respecting tenant visibility."""
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        matches = []
        visible_leagues = {league["league_id"] for league in db.all("league") if auth.visible(user, league["tenant_id"])}
        for match in db.all("match"):
            if match.get("league_id") not in visible_leagues:
                continue
            score = scoring.project(db, match["match_id"])
            status = match.get("operational_status") or score["status"]
            home, away = (db.get("club", match[side + "_team_id"], {}) for side in ("home", "away"))
            matches.append({"match_id": match["match_id"], "league_id": match["league_id"],
                "sport_id": match["sport_id"], "home_team_id": match["home_team_id"], "away_team_id": match["away_team_id"],
                "home_name": home.get("name", match["home_team_id"]), "away_name": away.get("name", match["away_team_id"]),
                "status": status, "scheduled_at": match.get("scheduled_at"), "venue": match.get("venue"),
                "home_score": score["home"]["score"], "away_score": score["away"]["score"]})
        priority = {"in_progress": 0, "upcoming": 1, "completed": 2}
        selected = []
        for sport in db.all("sport"):
            candidates = [m for m in matches if m["sport_id"] == sport["sport_id"]]
            if candidates:
                selected.append(min(candidates, key=lambda m: (priority.get(m["status"], 3), m["scheduled_at"] or "9999", m["match_id"])))
        return selected


@app.get("/api/v1/matches/{match_id}", response_model=dict)
def match_detail(match_id: str, request: Request):
    with transaction() as db:
        return app_match(db, match_id, auth.authenticate(db, request.headers.get("authorization")))


@app.get("/api/v1/matches/{match_id}/stream")
def match_stream(match_id: str, request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        app_match(db, match_id, user)

    async def updates():
        last = None
        while not await request.is_disconnected():
            with transaction() as db:
                current = canonical(app_match(db, match_id, user))
            if current != last:
                yield "event: update\ndata: " + current + "\n\n"
                last = current
            else:
                yield ": keepalive\n\n"
            await asyncio.sleep(1)
    return StreamingResponse(updates(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/api/v1/standings/{league_id}", response_model=dict)
def app_standings(league_id: str, request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        auth.tenant_access(user, db.require("league", league_id)["tenant_id"])
        return standings.standings(db, league_id)


@app.get("/api/v1/clubs/{club_id}", response_model=dict)
def club_profile(club_id: str, request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        club = db.require("club", club_id)
        auth.tenant_access(user, club["tenant_id"])
        squads = {s["squad_id"] for s in db.all("squad") if s["club_id"] == club_id}
        players = []
        for p in db.all("player"):
            p = roster.materialize(db, p)
            reg = p["registration"]
            if reg and reg["squad_id"] in squads:
                players.append({"player_id": p["player_id"], "name": p["name"], "position": p["position"], "role": reg["role"], "squad_id": reg["squad_id"]})
        return {**club, "roster": players}


@app.post("/api/v1/clubs/{club_id}/players", response_model=dict, status_code=201)
def add_club_player(club_id: str, request: Request, body: dict = Body(...)):
    """Club Manager adds a new player to their own club's squad (mobile roster screen)."""
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        auth.authorize(db, user, "roster", club_id)
        return roster.add_player(db, club_id, body, user)


@app.get("/api/v1/products", response_model=list[dict])
def products(request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        return [product_json(db, p) for p in db.all("product") if auth.visible(user, p["tenant_id"])]


@app.post("/api/v1/matches/{match_id}/pin", response_model=dict)
def pin_product(match_id: str, request: Request, body: PinRequest):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        match = db.require("match", match_id)
        auth.tenant_access(user, match["tenant_id"])
        if user["role"] != "super_admin" and (user["role"] != "league_organizer" or user["league_id"] != match.get("league_id")):
            fail(403, "FORBIDDEN")
        pid = body.product_id
        if pid is not None:
            identifier(pid, "product_id")
            product = db.require("product", pid)
            check(product["tenant_id"] == match["tenant_id"], "product_id")
        match["pinned_product_id"] = pid
        db.put("match", match_id, match)
        return app_match(db, match_id, user)


@app.get("/api/v1/sports", response_model=list[dict])
def app_sports(request: Request):
    with transaction() as db:
        auth.authenticate(db, request.headers.get("authorization"))
        return db.all("sport")


@app.get("/api/v1/sports/{sport_id}", response_model=dict)
def app_sport(sport_id: str, request: Request):
    with transaction() as db:
        auth.authenticate(db, request.headers.get("authorization"))
        return db.require("sport", sport_id)


@app.put("/api/v1/venues/{venue_id}", response_model=dict)
def configure_venue(venue_id: str, request: Request, body: dict = Body(...)):
    from .common import text, timestamp
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        if user["role"] not in ("super_admin", "league_organizer"):
            fail(403, "FORBIDDEN")
        identifier(venue_id, "venue_id")
        text(body.get("name"), "name", 100)
        windows = body.get("availability", [])
        check(isinstance(windows, list), "availability")
        for window in windows:
            obj(window, "availability")
            check(timestamp(window.get("start"), "start") < timestamp(window.get("end"), "end"), "availability")
        previous = db.get("venue", venue_id)
        if previous:
            auth.tenant_access(user, previous["tenant_id"])
        return db.put("venue", venue_id, {"venue_id": venue_id, "tenant_id": previous["tenant_id"] if previous else user["tenant_id"], "name": body["name"], "availability": windows})


@app.post("/api/v1/matches/{match_id}/reschedule", response_model=dict)
def reschedule_match(match_id: str, request: Request, body: dict = Body(...)):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        match = db.require("match", match_id)
        auth.authorize(db, user, "fixtures", match.get("league_id"))
        venue = db.require("venue", body.get("venue_id", ""))
        auth.tenant_access(user, venue["tenant_id"])
        return scheduling.reschedule(db, match_id, body)


@app.post("/api/v1/matches/{match_id}/status", response_model=dict)
def match_status(match_id: str, request: Request, body: dict = Body(...)):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        match = db.require("match", match_id)
        auth.authorize(db, user, "fixtures", match.get("league_id"))
        result = scheduling.change_status(db, match_id, body)
    live.bump("match:" + match_id)
    return result


@app.get("/api/v1/orders", response_model=list[dict])
def app_orders(request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        return [orders.public_order(order) for order in db.all("order") if orders.visible_order(user, order)]


@app.get("/api/v1/orders/{order_id}", response_model=dict)
def app_order(order_id: str, request: Request):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        order = db.require("order", order_id)
        if not orders.visible_order(user, order):
            fail(403, "FORBIDDEN")
        return orders.public_order(order)


@app.patch("/api/v1/orders/{order_id}/status", response_model=dict)
def order_status(order_id: str, request: Request, body: OrderStatusRequest):
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        return orders.transition(db, order_id, body.data(), user)


@app.patch("/api/v1/leagues/{league_id}/configuration", response_model=dict)
def league_configuration(league_id: str, request: Request, body: LeagueConfigurationRequest):
    from .common import integer, text
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        auth.authorize(db, user, "fixtures", league_id)
        league = db.require("league", league_id)
        config = body.data()
        if "minimum_rest_minutes" in config:
            integer(config["minimum_rest_minutes"], "minimum_rest_minutes", 0)
        if "season" in config:
            text(config["season"], "season", 80)
        if "format" in config:
            check(config["format"] in ("round_robin", "swiss", "single_elim", "double_elim", "hybrid"), "format")
        seen = set()
        for division in config.get("divisions", []):
            did = identifier(division.get("division_id"), "division_id")
            text(division.get("name"), "division.name", 80)
            teams = division.get("teams")
            check(isinstance(teams, list) and all(isinstance(t, str) and t in league["teams"] and t not in seen for t in teams), "division.teams")
            check(len(teams) == len(set(teams)), "division.teams")
            seen.update(teams)
        for key, value in config.get("promotion_relegation", {}).items():
            check(key in ("promote", "relegate"), "promotion_relegation")
            integer(value, "promotion_relegation." + key, 0)
        league.update(config)
        db.put("league", league_id, league)
        return {"league_id": league_id, "configuration": {k: league[k] for k in LeagueConfigurationRequest.model_fields if k in league}}


@app.patch("/api/v1/tenants/{tenant_id}/branding", response_model=dict)
def tenant_branding(tenant_id: str, request: Request, body: TenantBrandingRequest):
    import re
    from .common import text
    with transaction() as db:
        user = auth.authenticate(db, request.headers.get("authorization"))
        auth.authorize(db, user, "config")
        tenant = db.require("tenant", tenant_id)
        fields = body.data()
        if "name" in fields:
            text(fields["name"], "name", 100)
        if "primary_color" in fields:
            check(isinstance(fields["primary_color"], str) and re.fullmatch(r"#[0-9A-Fa-f]{6}", fields["primary_color"]) is not None, "primary_color")
        if fields.get("logo_url") is not None:
            check(fields["logo_url"].startswith(("http://", "https://")), "logo_url")
        tenant.update(fields)
        return db.put("tenant", tenant_id, tenant)
