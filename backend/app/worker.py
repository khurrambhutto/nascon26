"""Deliver durable order outbox events to Redis Streams and PDFs to MinIO.

Delivery is at least once; event_id is stable for consumer deduplication.
"""
import base64
import hashlib
import hmac
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlsplit

import httpx
import redis

from .common import canonical
from .storage import initialize, transaction
from .receipt_email import deliver_pending

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)


def upload(path, payload):
    endpoint = os.environ["OBJECT_STORE_URL"].rstrip("/")
    access = os.environ["OBJECT_STORE_ACCESS_KEY"]
    secret = os.environ["OBJECT_STORE_SECRET_KEY"]
    host = urlsplit(endpoint).netloc
    instant = datetime.now(timezone.utc)
    date, stamp = instant.strftime("%Y%m%d"), instant.strftime("%Y%m%dT%H%M%SZ")
    digest = hashlib.sha256(payload).hexdigest()
    uri = "/" + quote(path, safe="/-_.")
    signed = "host;x-amz-content-sha256;x-amz-date"
    headers = f"host:{host}\nx-amz-content-sha256:{digest}\nx-amz-date:{stamp}\n"
    request = f"PUT\n{uri}\n\n{headers}\n{signed}\n{digest}"
    scope = f"{date}/us-east-1/s3/aws4_request"
    string = f"AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{hashlib.sha256(request.encode()).hexdigest()}"
    key = ("AWS4" + secret).encode()
    for part in (date, "us-east-1", "s3", "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, string.encode(), hashlib.sha256).hexdigest()
    response = httpx.put(endpoint + uri, content=payload, headers={"Host": host, "x-amz-content-sha256": digest, "x-amz-date": stamp, "Authorization": f"AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={signed}, Signature={signature}"}, timeout=5)
    if response.status_code == 409 and "/" not in path:  # bucket already exists
        return
    response.raise_for_status()


RECORDINGS = Path(os.environ.get("RECORDINGS_DIR", "/recordings"))


def archive_recordings():
    """FR-3.2: upload finished MediaMTX recordings to MinIO and index them as VODs.

    A file counts as finished once MediaMTX has stopped writing it for 5 seconds,
    which happens right after the broadcast ends (or its key is revoked).
    """
    for file in sorted(RECORDINGS.glob("live/*/*.mp4")):
        key, vod_id = file.parent.name, file.parent.name + "-" + file.stem
        if time.time() - file.stat().st_mtime < 5:
            continue
        with transaction() as db:
            if db.get("vod", vod_id):
                continue
            stream = db.get("stream_key", key)
        upload("vod", b"")
        upload(f"vod/{key}/{file.name}", file.read_bytes())
        with transaction() as db:
            db.put("vod", vod_id, {"vod_id": vod_id, "player_id": stream["player_id"] if stream else None, "stream_key": key,
                                    "object": f"vod/{key}/{file.name}", "size_bytes": file.stat().st_size,
                                    "recorded_at": datetime.fromtimestamp(file.stat().st_mtime, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")})
        log.info("Archived VOD %s", vod_id)


def run():
    initialize()
    queue = redis.Redis.from_url(os.environ["REDIS_URL"], socket_timeout=3)
    while True:
        try:
            with transaction() as db:
                pending = [e for e in db.all("outbox") if not e["published"] or not e["archived"]]
            for event in pending:
                if not event["published"]:
                    queue.xadd("domain-events", {"event_id": event["event_id"], "payload": canonical({k: v for k, v in event.items() if k not in ("published", "archived")})})
                    event["published"] = True
                    with transaction() as db:
                        if db.get("outbox", event["event_id"]):
                            db.put("outbox", event["event_id"], event)
                if not event["archived"]:
                    with transaction() as db:
                        order = db.get("order", event["order_id"])
                    if order:
                        upload("receipts", b"")
                        upload("receipts/" + event["order_id"] + ".pdf", base64.b64decode(order["response"]["receipt"]["pdf_base64"]))
                    event["archived"] = True
                with transaction() as db:
                    if db.get("outbox", event["event_id"]):
                        db.put("outbox", event["event_id"], event)
        except Exception:
            log.exception("Outbox delivery failed; retrying")
        try:
            archive_recordings()
        except Exception:
            log.exception("VOD archiving failed; retrying")
        try:
            deliver_pending()
        except Exception:
            log.exception("Receipt email worker failed; retrying")
        time.sleep(1)


if __name__ == "__main__":
    run()
