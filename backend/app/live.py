"""In-process change counters that let WebSocket handlers push updates within ~100 ms.

Writers call bump(key) after committing; each socket polls versions[key] (memory only)
and reloads from the database only when the number changes. The API runs a single
uvicorn worker, so this memory is shared by every request.
"""
from collections import defaultdict

versions = defaultdict(int)
connections = 0


def bump(key):
    versions[key] += 1
