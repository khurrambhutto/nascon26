"""Contract errors, strict input checks and canonical signing."""
import hashlib
import hmac
import json
import os
import re
from datetime import date, datetime, timezone

DAY1_SALT = os.environ.get("DAY1_SALT", "").encode("utf-8")


class DomainError(Exception):
    def __init__(self, status, code, message=None, **details):
        self.status = status
        self.body = {"error": {"code": code, "message": message or code.replace("_", " ").capitalize(), "details": details}}


def fail(status, code, **details):
    raise DomainError(status, code, **details)


def invalid(*fields):
    fail(400, "VALIDATION_ERROR", fields=list(fields))


def check(condition, *fields):
    if not condition:
        invalid(*fields)


def obj(value, field="body"):
    check(isinstance(value, dict), field)
    return value


def integer(value, field, minimum=None, maximum=None):
    check(type(value) is int, field)
    check(minimum is None or value >= minimum, field)
    check(maximum is None or value <= maximum, field)
    return value


def identifier(value, field="id"):
    check(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", value) is not None, field)
    return value


def text(value, field, maximum=500):
    check(isinstance(value, str) and 1 <= len(value) <= maximum, field)
    return value


def day(value, field):
    check(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None, field)
    try:
        return date.fromisoformat(value)
    except ValueError:
        invalid(field)


def timestamp(value, field):
    check(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value) is not None, field)
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        invalid(field)


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def canonical(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError):
        invalid("payload")


def sign(record_id, payload):
    return hmac.new(DAY1_SALT, (record_id + "|" + canonical(payload)).encode("utf-8"), hashlib.sha256).hexdigest()


TIE_BREAKS = ["h2h_points", "h2h_diff", "overall_diff", "scored", "fair_play", "draw_lots"]


def tie_breaks(value):
    check(isinstance(value, list) and all(isinstance(x, str) and x in TIE_BREAKS for x in value), "tie_break_order")
    check(len(value) == len(set(value)), "tie_break_order")
    return [x for x in value if x != "draw_lots"] + ["draw_lots"]
