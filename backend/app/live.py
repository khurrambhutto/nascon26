"""Change counters that let WebSocket handlers push updates within ~100 ms.

Writers call bump(key) after committing; each socket polls version(key) and reloads
from the database only when it changes. The API runs several uvicorn workers, so the
counters live in Redis (shared by all workers); tests without Redis fall back to memory.
"""
import os
from collections import defaultdict

import redis

client = redis.Redis.from_url(os.environ["REDIS_URL"], socket_connect_timeout=1, socket_timeout=1) if os.environ.get("REDIS_URL") else None
memory = defaultdict(int)


def bump(key):
    if client is not None:
        try:
            client.incr("live:" + key)
            return
        except redis.RedisError:
            pass
    memory[key] += 1


def version(key):
    """Changes when the key is bumped or after any /audit/v1/reset (epoch)."""
    if client is not None:
        try:
            return tuple(client.mget("live:" + key, "live:epoch"))
        except redis.RedisError:
            pass
    return memory[key], memory["epoch"]


cache = {}


def cached(name, compute):
    """Per-worker read cache, valid until any write bumps "data" (or a reset bumps "epoch").

    The version is read before computing, so a write that races the computation
    simply makes the next request recompute.
    """
    current = version("data")
    hit = cache.get(name)
    if hit and hit[0] == current:
        return hit[1]
    value = compute()
    cache[name] = (current, value)
    return value


def connected(delta):
    if client is not None:
        try:
            client.incrby("live:connections", delta)
            return
        except redis.RedisError:
            pass
    memory["connections"] += delta


def connections():
    if client is not None:
        try:
            return max(int(client.get("live:connections") or 0), 0)
        except redis.RedisError:
            pass
    return memory["connections"]
