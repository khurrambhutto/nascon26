"""Durable, acknowledged user notifications with bounded delivery attempts."""
import time
from uuid import uuid4
from . import live, moderation


def enqueue(db, user_id, kind, payload, event_id=None):
    key = event_id or uuid4().hex
    if not db.get('notification', key):
        db.put('notification', key, {'event_id': key, 'user_id': user_id, 'type': kind, 'payload': payload,
                                   'attempts': 0, 'next_attempt': time.time(), 'status': 'pending'})
    live.bump('notifications:' + user_id)
    return key


def pending(db, user_id, moment=None):
    moment = time.time() if moment is None else moment
    result = []
    for event in db.all('notification'):
        if event['user_id'] != user_id or event['status'] != 'pending' or event['next_attempt'] > moment:
            continue
        if event['attempts'] >= 3:
            event['status'] = 'failed'
            moderation.dead_letter(db, event['type'], user_id, 'Subscriber did not acknowledge delivery', event_id=event['event_id'])
        else:
            event['attempts'] += 1
            event['next_attempt'] = moment + 2 ** (event['attempts'] - 1)
            result.append(dict(event))
        db.put('notification', event['event_id'], event)
    return result


def acknowledge(db, user_id, event_id):
    from .common import fail
    event = db.require('notification', event_id)
    if event['user_id'] != user_id:
        fail(403, 'FORBIDDEN')
    event['status'] = 'delivered'
    db.put('notification', event_id, event)
    return {'event_id': event_id, 'status': 'delivered'}


def expire_deliveries(db):
    # Disconnected recipients still receive three delivery attempts, then a durable DLQ event.
    for user_id in {e['user_id'] for e in db.all('notification') if e['status'] == 'pending'}:
        subscriber = db.get("notification_subscriber", user_id, {})
        if time.time() - subscriber.get("seen", 0) > 1:
            pending(db, user_id)
