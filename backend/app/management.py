"""Authenticated management operations used by the mobile application."""
from uuid import uuid4

from fastapi import APIRouter, Request
from pydantic import Field

from . import auth, scoring, offline, scheduling, store as merchandise, receipt_email
from .common import check, fail, obj, timestamp
from .schemas import Input, OfflineRequest, CheckoutRequest
from .storage import transaction

router = APIRouter(prefix="/api/v1")


class AppCheckoutRequest(CheckoutRequest):
    receipt_email: str | None = None


def default_receipt_address(user):
    import os
    if user["email"].lower().endswith(".test"):
        return os.environ.get("DEMO_RECEIPT_EMAIL") or (user["email"] if receipt_email.delivery_mode() == "local" else None)
    return user["email"]


@router.get("/checkout/preferences", response_model=dict)
def checkout_preferences(request: Request):
    with transaction() as db:
        user = user_for(db, request)
        return {"default_receipt_email": default_receipt_address(user), "email_delivery_configured": receipt_email.configuration() is not None,
            "delivery_mode": receipt_email.delivery_mode()}


@router.post("/checkout", response_model=dict, status_code=201)
def mobile_checkout(body: AppCheckoutRequest, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        if body.customer_id != user["user_id"]:
            fail(403, "FORBIDDEN")
        recipient = body.receipt_email
        if recipient is None:
            recipient = default_receipt_address(user)
        elif not recipient.strip():
            recipient = None  # An explicitly empty field opts out of email delivery.
        recipient = receipt_email.address(recipient)
        # Every variant must belong to a product this tenant can access.
        for item in body.items:
            product = next((p for p in db.all("product") if item.variant_id in p["variant_ids"]), None)
            if product is None:
                db.require("variant", item.variant_id)
                fail(404, "VARIANT_NOT_FOUND")
            auth.tenant_access(user, product["tenant_id"])
        previous = db.get("order", body.order_id)
        if previous and previous.get("receipt_email") != recipient:
            fail(409, "DUPLICATE_ORDER_ID")
        response, created = merchandise.checkout(db, body.model_dump(exclude={"receipt_email"}))
        if created:
            order = db.require("order", body.order_id)
            order["receipt_email"] = recipient
            db.put("order", body.order_id, order)
            if recipient:
                receipt_email.enqueue(db, body.order_id, recipient)
        return {**response, "receipt_delivery": receipt_email.public_delivery(db.get("receipt_email", body.order_id))}


@router.get("/orders/{order_id}/receipt-email", response_model=dict)
def delivery_status(order_id: str, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        order = db.require("order", order_id)
        if user["role"] != "super_admin" and order["request"]["customer_id"] != user["user_id"]:
            fail(403, "FORBIDDEN")
        return receipt_email.public_delivery(db.get("receipt_email", order_id))


class LeagueCreate(Input):
    name: str = Field(min_length=1, max_length=100)
    sport_id: str
    teams: list[str] = Field(min_length=2, max_length=40)
    tenant_id: str | None = None


class MatchCreate(Input):
    league_id: str
    home_team_id: str
    away_team_id: str
    scheduled_at: str
    venue: str = Field(min_length=1, max_length=120)
    duration_minutes: int = Field(default=90, ge=1, le=1440)


class VariantCreate(Input):
    label: str = Field(min_length=1, max_length=80)
    price_minor: int = Field(ge=0)
    stock: int = Field(ge=0)
    size: str | None = None
    color: str | None = None


class ProductCreate(Input):
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    category: str = Field(min_length=1, max_length=80)
    club_id: str
    variants: list[VariantCreate] = Field(min_length=1, max_length=50)


class VariantUpdate(Input):
    price_minor: int | None = Field(default=None, ge=0)
    stock: int | None = Field(default=None, ge=0)


def user_for(db, request):
    return auth.authenticate(db, request.headers.get("authorization"))


def manage_product(db, user, product):
    auth.tenant_access(user, product["tenant_id"])
    if user["role"] != "super_admin" and not (
        user["role"] == "club_manager" and user["club_id"] == product["club_id"]
    ):
        fail(403, "FORBIDDEN")


@router.get("/clubs", response_model=list[dict])
def clubs(request: Request):
    with transaction() as db:
        user = user_for(db, request)
        return [c for c in db.all("club") if auth.visible(user, c["tenant_id"])]


@router.get("/leagues/{league_id}/management", response_model=dict)
def league_details(league_id: str, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        league = db.require("league", league_id)
        auth.authorize(db, user, "fixtures", league_id)
        return league


@router.post("/leagues", response_model=dict, status_code=201)
def create_league(body: LeagueCreate, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        # Organizers are bound to an assigned league; platform admins provision new leagues.
        auth.authorize(db, user, "config")
        check(bool(body.name.strip()), "name")
        tenant = body.tenant_id or user["tenant_id"]
        db.require("tenant", tenant)
        check(len(set(body.teams)) == len(body.teams), "teams")
        check(all(db.require("club", cid)["tenant_id"] == tenant for cid in body.teams), "teams")
        lid = "league-" + uuid4().hex
        scoring.register_league(db, {"league_id": lid, "name": body.name.strip(),
            "sport_id": body.sport_id, "tenant_id": tenant, "teams": body.teams,
            "fixtures": [], "qualification": {"spots": 1}})
        return db.require("league", lid)


@router.post("/matches", response_model=dict, status_code=201)
def create_match(body: MatchCreate, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        auth.authorize(db, user, "fixtures", body.league_id)
        league = db.require("league", body.league_id)
        check(bool(body.venue.strip()), "venue")
        check(body.home_team_id != body.away_team_id, "away_team_id")
        check(body.home_team_id in league["teams"] and body.away_team_id in league["teams"], "teams")
        timestamp(body.scheduled_at, "scheduled_at")
        venue = next((v for v in db.all("venue") if v["tenant_id"] == league["tenant_id"]
            and v["name"].casefold() == body.venue.strip().casefold()), None)
        if venue is None:
            vid = "venue-" + uuid4().hex
            venue = db.put("venue", vid, {"venue_id": vid, "tenant_id": league["tenant_id"],
                "name": body.venue.strip(), "availability": []})
        mid = "match-" + uuid4().hex
        match = {"match_id": mid, "league_id": body.league_id, "sport_id": league["sport_id"],
            "tenant_id": league["tenant_id"], "home_team_id": body.home_team_id,
            "away_team_id": body.away_team_id, "scheduled_at": body.scheduled_at,
            "venue": venue["name"], "venue_id": venue["venue_id"],
            "duration_minutes": body.duration_minutes, "events": [], "pinned_product_id": None}
        collisions = scheduling.conflicts(db, match, body.scheduled_at, body.duration_minutes, venue["venue_id"])
        if collisions:
            fail(409, "SCHEDULE_CONFLICT", conflicts=collisions)
        db.put("match", mid, match)
        league["fixtures"].append({"match_id": mid, "home": body.home_team_id, "away": body.away_team_id,
            "scheduled_at": body.scheduled_at, "venue": body.venue.strip()})
        db.put("league", body.league_id, league)
        return match


@router.get("/management/products", response_model=list[dict])
def managed_products(request: Request):
    with transaction() as db:
        user = user_for(db, request)
        if user["role"] not in ("super_admin", "club_manager"):
            fail(403, "FORBIDDEN")
        return [{**p, "variants": [db.require("variant", v) for v in p["variant_ids"]]}
            for p in db.all("product") if auth.visible(user, p["tenant_id"]) and (
                user["role"] == "super_admin" or p["club_id"] == user["club_id"])]


@router.post("/products", response_model=dict, status_code=201)
def create_product(body: ProductCreate, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        club = db.require("club", body.club_id)
        check(bool(body.name.strip()) and bool(body.category.strip()), "name", "category")
        check(all(bool(v.label.strip()) for v in body.variants), "variants.label")
        product = {"product_id": "prod-" + uuid4().hex, "tenant_id": club["tenant_id"],
            "club_id": body.club_id, "name": body.name.strip(), "description": body.description,
            "category": body.category, "variant_ids": []}
        manage_product(db, user, product)
        variants = []
        for variant in body.variants:
            vid = "var-" + uuid4().hex
            variants.append(db.put("variant", vid, {**variant.model_dump(), "variant_id": vid, "currency": "PKR"}))
            product["variant_ids"].append(vid)
        db.put("product", product["product_id"], product)
        return {**product, "variants": variants}


@router.patch("/products/{product_id}/variants/{variant_id}", response_model=dict)
def update_variant(product_id: str, variant_id: str, body: VariantUpdate, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        product = db.require("product", product_id)
        manage_product(db, user, product)
        check(variant_id in product["variant_ids"], "variant_id")
        changes = body.model_dump(exclude_none=True)
        check(bool(changes), "stock", "price_minor")
        variant = db.require("variant", variant_id)
        variant.update(changes)
        return db.put("variant", variant_id, variant)


@router.post("/offline-sync", response_model=dict)
def sync_actions(body: OfflineRequest, request: Request):
    with transaction() as db:
        user = user_for(db, request)
        data = body.data()
        for action in data["actions"]:
            obj(action, "actions")
            check(action.get("type") in ("score_event", "roster_transfer"), "type")
            payload = obj(action.get("payload"), "payload")
            if action["type"] == "score_event":
                match = db.require("match", payload.get("match_id", ""))
                auth.authorize(db, user, "fixtures", match.get("league_id"))
            else:
                squad = db.require("squad", payload.get("from_squad_id", ""))
                auth.authorize(db, user, "roster", squad["club_id"])
                check(payload.get("requested_by") == user["user_id"], "requested_by")
        return offline.sync(db, data)
