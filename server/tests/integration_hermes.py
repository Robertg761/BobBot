"""Run with Hermes' venv and PYTHONPATH pointing to its checkout. Uses a temp home."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

with tempfile.TemporaryDirectory(prefix='bobbot-integration-') as scratch:
    os.environ['HERMES_HOME'] = scratch
    os.environ.pop('HERMES_KANBAN_TASK', None)
    os.environ.pop('HERMES_KANBAN_BOARD', None)
    home = Path(scratch)
    plugin = Path(__file__).resolve().parents[1] / 'bobbot-team'
    for base in [home, home / 'profiles' / 'research']:
        (base / 'plugins').mkdir(parents=True)
        (base / 'config.yaml').write_text('model:\n  default: test-model\nplugins:\n  enabled: [bobbot-team]\nplatform_toolsets:\n  cli: [kanban, bobbot_team]\n')
        (base / 'plugins' / 'bobbot-team').symlink_to(plugin, target_is_directory=True)
    from hermes_constants import set_hermes_home_override, reset_hermes_home_override
    from hermes_cli import kanban_db
    from hermes_cli.kanban_db_connect import connect
    spec = importlib.util.spec_from_file_location('bobbot_integration', plugin / '__init__.py', submodule_search_locations=[str(plugin)])
    team = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = team
    spec.loader.exec_module(team)
    db = connect()
    task = kanban_db.create_task(db, title='Permission integration fixture', assignee='research')
    os.environ['HERMES_KANBAN_TASK'] = task
    token = set_hermes_home_override(str(home / 'profiles' / 'research'))
    try:
        assert team.current_profile() == 'research'
        assert team.gate('kanban_complete', {}, session_id='fixture')['action'] == 'block'
        assert team.gate('kanban_request_review', {'summary': 'Done'}, session_id='fixture')['args']['reviewer'] == 'default'
        from hermes_cli.plugins import discover_plugins, get_pre_tool_call_directive
        discover_plugins()
        directive, message = get_pre_tool_call_directive('terminal', {'command': 'pwd'}, session_id='fixture-session')
        assert directive == 'block', (directive, message)
        request = team.store().requests()[0]
        assert request['review_task']
        review = kanban_db.get_task(db, request['review_task'])
        assert review.assignee == 'default'
        assert json.loads(team.decide({'request_id': request['id'], 'choice': 'approved', 'reason': 'self approval'})).get('error')
        # Real hook rewrites permission waits to dependencies, avoiding Hermes' repeated-failure breaker.
        from hermes_cli.plugins import _get_pre_tool_call_directive_details
        directive = _get_pre_tool_call_directive_details('kanban_block', {'reason': 'Awaiting permission ' + request['id'], 'kind': 'needs_input'}, session_id='fixture-session')
        assert directive.modified_args['kind'] == 'dependency'
        # The synthetic worker was only ready; the new parent correctly moved it to todo.
        assert kanban_db.get_task(db, task).status == 'todo'
    finally:
        reset_hermes_home_override(token)
    assert team.current_profile() == 'default'
    decision = json.loads(team.decide({'request_id': request['id'], 'choice': 'approved', 'reason': 'Read current directory'}))
    assert decision['status'] == 'approved', decision
    assert kanban_db.get_task(db, task).status == 'ready'
    token = set_hermes_home_override(str(home / 'profiles' / 'research'))
    try:
        assert team.gate('terminal', {'command': 'pwd'}, session_id='resumed-session') is None
        assert team.gate('terminal', {'command': 'pwd'}, session_id='resumed-session')['action'] == 'block'
    finally:
        reset_hermes_home_override(token)
    # Real dashboard plugin import and validation, without touching the running server.
    api_spec = importlib.util.spec_from_file_location('bobbot_dashboard_test', plugin / 'dashboard' / 'plugin_api.py')
    api = importlib.util.module_from_spec(api_spec)
    sys.modules[api_spec.name] = api
    api_spec.loader.exec_module(api)
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    app = FastAPI()
    app.include_router(api.router)
    client = TestClient(app)
    response = client.get('/team')
    assert response.status_code == 200, response.text
    assert response.json()['authority'] == 'default'
    assert client.put('/team', json={'authority': '../../invalid', 'enabled': True}).status_code in (400, 422)
    # Native hosted-room execution continues independently of a client transport.
    import threading
    import time
    from types import SimpleNamespace
    from tui_gateway.hosted_room_service import HostedRoomService
    from gateway import hosted_rooms
    fake_server = SimpleNamespace(_methods={}, _sessions={}, _sessions_lock=threading.Lock())
    fake_server._methods['session.list'] = lambda rid, params: {'result': {'sessions': []}}
    fake_server._methods['session.create'] = lambda rid, params: {'result': {'session_id': 'live-' + params['profile']}}
    fake_server._methods['session.resume'] = lambda rid, params: {'result': {'session_id': params['session_id']}}
    fake_server._methods['session.history'] = lambda rid, params: {'result': {'messages': []}}
    def submit(rid, params):
        params['_hosted_terminal_callback']({'status': 'settled', 'text': 'Research result.'})
        return {'result': {'accepted': True}}
    fake_server._methods['prompt.submit'] = submit
    service = HostedRoomService(fake_server, db_path=home / 'rooms.db')
    room = service.create_room(room_id='fixture-room', name='Clove and research', members=[
        {'member_id': 'default', 'profile': 'default', 'handle': 'default', 'display_name': 'Clove'},
        {'member_id': 'research', 'profile': 'research', 'handle': 'research', 'display_name': 'Research'}])
    assert room['room_id'] == 'fixture-room'
    service.start()
    try:
        payload = {'text': '@research inspect this fixture', 'thread_id': 'main'}
        service.send(room_id='fixture-room', event_id='fixture-message', payload=payload)
        service.send(room_id='fixture-room', event_id='fixture-message', payload=payload)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            events = hosted_rooms.read_events(home / 'rooms.db', room_id='fixture-room', since_seq=0)['events']
            if any(e['kind'] == 'message.member' for e in events):
                break
            time.sleep(0.05)
        else:
            raise AssertionError('Hosted group did not produce a reply')
        assert sum(e['kind'] == 'message.user' for e in events) == 1
        reply = next(e for e in events if e['kind'] == 'message.member')
        assert reply['payload']['text'] == 'Research result.'
        assert reply['actor']['profile'] == 'research'
    finally:
        assert service.stop(timeout=5)
    reopened = HostedRoomService(fake_server, db_path=home / 'rooms.db')
    assert reopened._events('fixture-room') == events
    db.close()
    print('Native hosted-group test passed: named member, idempotent send, server-run reply and restart replay.')
    print('Hermes integration passed: profile-scoped hook, durable review task, no self approval, approved retry, task wakeup, dashboard routes.')
