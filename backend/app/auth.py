"""Five-role policy matrix, tenant/ownership checks and verified JWTs."""
import hashlib
import hmac
import os
import secrets
import time

import jwt

from .common import DAY1_SALT, fail

ROLES = {"admin": "super_admin", "organizer": "league_organizer", "manager": "club_manager", "player": "player", "viewer": "viewer"}


def secret():
    return os.environ.get("JWT_SECRET") or hmac.new(DAY1_SALT, b"nascon-jwt-signing-key-v1", hashlib.sha256).hexdigest()


def password_hash(password, email):
    salt = secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 120000).hex()
    return "pbkdf2_sha256$120000$" + salt.hex() + "$" + derived


def verify_password(password, encoded):
    try:
        algorithm, rounds, salt, expected = encoded.split("$")
        if algorithm != "pbkdf2_sha256" or int(rounds) != 120000:
            return False
        actual = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), int(rounds)).hex()
        return hmac.compare_digest(expected, actual)
    except (ValueError, TypeError):
        return False


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
    if not user or user.get("erased"):
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
    if not user or not verify_password(password, user["password_hash"]):
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


def register(store, body):
    from uuid import uuid4
    from .common import check, text
    email = text(body.get("email"), "email", 254).strip().lower()
    password = text(body.get("password"), "password", 256)
    name = text(body.get("name"), "name", 100)
    check("@" in email and len(password) >= 8, "email", "password")
    if any(u["email"].lower() == email for u in store.all("user")):
        fail(409, "EMAIL_ALREADY_REGISTERED")
    user = {"user_id": "u-" + uuid4().hex, "email": email, "name": name, "role": "viewer", "tenant_id": "tenant-alpha",
            "player_id": None, "league_id": None, "club_id": None, "password_hash": password_hash(password, email)}
    store.put("user", user["user_id"], user)
    return {"token": token(user), **identity(store, user)}


def ensure_demo_players(store):
    for suffix, player_id, tenant in [("2", "player-alpha-2", "tenant-alpha"), ("3", "player-beta-1", "tenant-beta"), ("4", "player-beta-2", "tenant-beta")]:
        uid = "u-player" + suffix
        if store.get("player", player_id) and not store.get("user", uid):
            email = "player" + suffix + "@nascon.test"
            store.put("user", uid, {"user_id": uid, "email": email, "name": "Player " + suffix, "role": "player", "tenant_id": tenant,
                "player_id": player_id, "league_id": None, "club_id": None, "password_hash": password_hash("Nascon@26", email)})
