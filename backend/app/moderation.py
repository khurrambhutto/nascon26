"""FR-5.2 / FR-5.3 pre-broadcast chat moderation and the notification Dead-Letter Queue."""
import re
from uuid import uuid4

from .common import check, now, obj, sign2

PROFANITY = {"badword", "porn", "scam", "fuck", "shit", "bitch", "asshole", "idiotspam"}
LEET = str.maketrans({"@": "a", "0": "o", "1": "i", "3": "e", "$": "s"})
SEPARATORS = re.compile(r"[_.\-*​‌‍⁠﻿]")
EDGE_PUNCTUATION = ",;:?!\"'()[]{}"
REPEATED_CHARACTER = re.compile(r"(.)\1{3,}", re.IGNORECASE | re.DOTALL)  # 4+ identical characters, e.g. AAAA
REPEATED_MARKS = re.compile(r"!{3,}|\?{3,}")  # !!! or ???
MAX_ATTEMPTS = 3


def normalize(word, bang):
    """Undo leetspeak; '!' may stand for 'i' (s!c@m) or be plain punctuation."""
    word = SEPARATORS.sub("", word.lower()).translate(LEET)
    return word.replace("!", bang)


def is_profane(core):
    return any(normalize(core, bang) in PROFANITY or normalize(core, bang).rstrip("s") in PROFANITY for bang in ("i", ""))


def mask(text):
    """Replace every offending token with *** in place, keeping surrounding punctuation."""
    words, hit = [], False
    for word in text.split(" "):
        core = word.strip(EDGE_PUNCTUATION)
        if core and is_profane(core):
            start = word.index(core)
            words.append(word[:start] + "***" + word[start + len(core):])
            hit = True
        else:
            words.append(word)
    return " ".join(words), hit


def is_flood(text):
    return bool(REPEATED_CHARACTER.search(text) or REPEATED_MARKS.search(text))


def moderate(messages):
    """Pure function: (broadcastable messages, kicked senders, muted senders)."""
    filtered, kicked, muted, per_sender = [], [], [], {}
    for message in messages:
        sender = message["sender"]
        per_sender[sender] = per_sender.get(sender, 0) + 1
        if is_flood(message["text"]) or per_sender[sender] > 3:  # flood, or >3 messages in one burst
            if sender not in muted:
                muted.append(sender)
            continue
        text, hit = mask(message["text"])
        if hit and sender not in kicked:
            kicked.append(sender)
        filtered.append({"message_id": message["message_id"], "sender": sender, "text": text,
                         "flagged": hit, "reason": "profanity_leetspeak" if hit else None})
    return filtered, kicked, muted


def validate(body):
    obj(body)
    room = body.get("chat_room_id")
    check(isinstance(room, str) and room, "chat_room_id")
    messages = body.get("incoming_messages")
    check(isinstance(messages, list) and len(messages) <= 500, "incoming_messages")
    for message in messages:
        check(isinstance(message, dict) and all(isinstance(message.get(k), str) for k in ("message_id", "sender", "text")), "incoming_messages")
    check(isinstance(body.get("check_dlq", True), bool), "check_dlq")
    failed = body.get("simulate_failed_notifications", 0)
    check(type(failed) is int and 0 <= failed <= 1000, "simulate_failed_notifications")
    return room, messages, failed


def dead_letter(store, event_type, target, reason, event_id=None):
    """Record a notification that failed all bounded retries (1s, 2s, 4s backoff)."""
    event_id = event_id or "evt-" + uuid4().hex[:12]
    store.put("dlq", event_id, {"event_id": event_id, "type": event_type, "target": target, "attempts": MAX_ATTEMPTS,
                                "last_error": reason, "failed_at": now()})


def dlq_status(store):
    events = store.all("dlq")
    return {"queue_depth": len(events), "undelivered_events": events}


def response(room, filtered, kicked, muted, dlq):
    flagged = sum(1 for m in filtered if m["flagged"])
    signed = {"chat_room_id": room, "flagged_count": flagged, "kicked_users": kicked, "queue_depth": dlq["queue_depth"]}
    return {"chat_room_id": room, "filtered_messages": filtered, "kicked_users": kicked, "muted_users": muted,
            "dlq_status": dlq, "signature": sign2(room, signed)}
