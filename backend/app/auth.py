"""Five-role policy matrix, tenant/ownership checks and verified JWTs."""
import hashlib
import hmac
import os
import time

import jwt

from .common import fail

ROLES = {"admin": "super_admin", "organizer": "league_organizer", "manager": "club_manager", "player": "player", "viewer": "viewer"}


def secret():
    return os.environ.get("JWT_SECRET", "nascon-local-development-secret-change-for-deployment")


def password_hash(password, email):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), (email + secret()).encode(), 120000).hex()


def token(user):
    return jwt.encode({"sub": user["user_id"], "iat": int(time.time()), "exp": int(time.time()) + 8*3600}, secret(), algorithm="HS256")


def authenticate(store, header):
    if not header or not header.startswith("Bearer "):
        fail(401, "UNAUTHENTICATED")
    try:
        claims = jwt.decode(header[7:], secret(), algorithms=["HS256"], options={"require": ["sub", "exp", "iat"]})
    except jwt.PyJWTError:
        fail(401, "UNAUTHENTICATED")
    user = store.get("user", claims["sub"])
    if not user:
        fail(401, "UNAUTHENTICATED")
    return user


def public_user(user):
    return {key: value for key, value in user.items() if key != "password_hash"}


def identity(store, user):
    return {"user": public_user(user), "tenant": store.require("tenant", user["tenant_id"])}


def login(store, body):
    from .common import obj, text
    obj(body)
    email = text(body.get("email"), "email", 254)
    password = text(body.get("password"), "password", 256)
    user = next((u for u in store.all("user") if u["email"] == email), None)
    if not user or not hmac.compare_digest(user["password_hash"], password_hash(password, email)):
        fail(401, "INVALID_CREDENTIALS")
    return {"token": token(user), **identity(store, user)}


def visible(user, tenant_id):
    return user["role"] == "super_admin" or user["tenant_id"] == tenant_id


def tenant_access(user, tenant_id):
    if not visible(user, tenant_id):
        fail(403, "FORBIDDEN")


def authorize(store, user, action, target=None):
    role = user["role"]
    resource = None
    if action == "fixtures":
        resource = store.require("league", target)
    elif action in ("roster", "products", "club"):
        resource = store.require("club", target)
    elif action == "player":
        resource = store.require("player", target)
        squad_id = resource["registration"]["squad_id"] if resource.get("registration") else resource.get("squad_id")
        squad = store.get("squad", squad_id) if squad_id else None
        resource = {**resource, "club_id": squad["club_id"] if squad else None, "tenant_id": squad["tenant_id"] if squad else "tenant-alpha"}
    if role == "super_admin":
        return
    if resource:
        tenant_access(user, resource["tenant_id"])
    allowed = action == "club"
    allowed |= action == "fixtures" and role == "league_organizer" and user["league_id"] == target
    allowed |= action in ("roster", "products") and role == "club_manager" and user["club_id"] == target
    allowed |= action == "player" and ((role == "player" and user["player_id"] == target) or (role == "club_manager" and resource["club_id"] == user["club_id"]))
    if not allowed:
        fail(403, "FORBIDDEN")


def matrix(store):
    return {"tokens": {role: token(store.require("user", "u-" + short)) for short, role in ROLES.items()}, "context": {"tenant_id": "tenant-alpha", "foreign_tenant_id": "tenant-beta", "own_league_id": "league-alpha-1", "other_league_id": "league-alpha-2", "foreign_league_id": "league-beta-1", "own_club_id": "club-alpha-1", "other_club_id": "club-alpha-2", "foreign_club_id": "club-beta-1", "own_player_id": "player-alpha-1", "other_player_id": "player-alpha-2", "foreign_player_id": "player-beta-1"}}
