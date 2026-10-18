import time
from app import notifications
from app.storage import transaction, reader


def test_real_moderation_persists_and_is_room_scoped(client, login):
    h = login('viewer')
    assert client.post('/api/v1/chat', headers=h, json={'chat_room_id': 'one', 'text': 'b@d_w0rd'}).json()['text'] == '***'
    assert client.post('/api/v1/chat', headers=h, json={'chat_room_id': 'one', 'text': 'hello'}).status_code == 403
    assert client.post('/api/v1/chat', headers=h, json={'chat_room_id': 'two', 'text': 'hello'}).status_code == 201
    assert client.post('/api/v1/chat', headers=h, json={'chat_room_id': 'two', 'text': 'AAAA'}).status_code == 429
    assert client.post('/api/v1/chat', headers=h, json={'chat_room_id': 'two', 'text': 'later'}).status_code == 403


def test_guest_expiration_and_registration(client):
    token = client.post('/api/v1/guest/session').json()['guest_token']
    with transaction() as db:
        record = db.get('guest_token', token);record['expires_at'] = time.time() - 1;db.put('guest_token', token, record)
    assert client.post('/api/v1/guest/cart', headers={'Authorization': 'Bearer '+token}, json={'variant_id':'var-jersey-001-L','quantity':1}).status_code == 401
    body = {'email':'new@example.test','password':'Password123','name':'New Viewer'}
    r = client.post('/api/v1/auth/register', json=body)
    assert r.status_code == 201 and r.json()['user']['role'] == 'viewer'
    assert client.post('/api/v1/auth/register', json=body).status_code == 409
    assert client.post('/api/v1/auth/login', json={k:body[k] for k in ('email','password')}).status_code == 200


def test_notification_three_attempts_then_dlq(client):
    with transaction() as db:
        key = notifications.enqueue(db, 'u-viewer', 'order.status_changed', {'status':'packed'})
        base = time.time()+1
        assert notifications.pending(db, 'u-viewer', base)[0]['attempts'] == 1
        assert notifications.pending(db, 'u-viewer', base+.5) == []
        assert notifications.pending(db, 'u-viewer', base+1)[0]['attempts'] == 2
        assert notifications.pending(db, 'u-viewer', base+3)[0]['attempts'] == 3
        assert notifications.pending(db, 'u-viewer', base+7) == []
        assert db.get('notification', key)['status'] == 'failed'
        assert db.get('dlq', key)['attempts'] == 3


def test_invitation_socket_ack_and_real_disconnect(client, login):
    player = login('player')
    invited = login('player2')
    session = client.post('/api/v1/costream', headers=player, json={}).json()['session_id']
    with client.websocket_connect('/ws/notifications', headers=invited) as socket:
        invite = client.post(f'/api/v1/costream/{session}/invites', headers=player, json={'player_id':'player-alpha-2'}).json()
        event = socket.receive_json()
        assert event['type'] == 'costream.invite'
        socket.send_json({'event_id':event['event_id']})
    assert client.post(f'/api/v1/costream/{session}/join',json=invite).status_code == 200
    with client.websocket_connect(f'/ws/costream/{session}/presence',headers=player):
        pass
    state = client.get('/api/v1/public/costream/'+session).json()
    assert state['active_host'] == 'player-alpha-2'
    assert client.post('/api/v1/costream/'+session+'/leave',headers=invited).json()['status'] == 'terminated'


def test_extra_demo_players_and_status_alias(client):
    for suffix in ('2','3','4'):
        r=client.post('/api/v1/auth/login', json={'email':'player'+suffix+'@nascon.test','password':'Nascon@26'})
        assert r.status_code==200
        assert client.post('/api/v1/me/stream-key',headers={'Authorization':'Bearer '+r.json()['token']},json={'action':'rotate'}).status_code==200
    assert client.post('/audit/v1/events',json={'event_id':'alias-pp','match_id':'match-m1','type':'postpone','payload':{}}).status_code==201
