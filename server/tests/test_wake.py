import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('team_wake', Path(__file__).parents[1] / 'bobbot-team' / 'wake.py')
wake = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wake)


class ReadOnlyTest(unittest.TestCase):
    def test_lookup_tools_never_need_review(self):
        self.assertTrue(wake.is_read_only('tool_search', {'queries': ['x']}))
        self.assertTrue(wake.is_read_only('tool_describe', {}))

    def test_cronjob_list_is_read_only_but_writes_are_not(self):
        self.assertTrue(wake.is_read_only('cronjob_manage', {'action': 'list'}))
        self.assertTrue(wake.is_read_only('cronjob_manage', {'action': ' List '}))
        for action in ('create', 'remove', 'run', 'update', ''):
            self.assertFalse(wake.is_read_only('cronjob_manage', {'action': action}))
        self.assertFalse(wake.is_read_only('terminal', {'command': 'ls'}))


class NudgeEnvTest(unittest.TestCase):
    def test_deciding_context_never_reaches_the_specialist(self):
        base = {'PATH': '/usr/bin', 'HOME': '/home/x', 'HERMES_HOME': '/home/x/.hermes/profiles/clove',
                'HERMES_KANBAN_TASK': 't1', 'HERMES_KANBAN_DB': '/x/kanban.db', 'HERMES_KANBAN_BOARD': 'main',
                'HERMES_PROFILE': 'clove', 'HERMES_TURN_AUTHOR': '{"id":"bot:clove"}', 'TERMINAL_CWD': '/x/ws',
                'HERMES_SESSION_ID': 's', 'HERMES_SESSION_PLATFORM': 'telegram', 'HERMES_SESSION_CHAT_ID': '42',
                'HERMES_CRON_AUTO_DELIVER_PLATFORM': 'telegram', 'HERMES_TUI': '1'}
        env = wake.nudge_env(base)
        self.assertEqual(env.get('PATH'), '/usr/bin')
        for key in base:
            if key.startswith(('HERMES_KANBAN_', 'HERMES_SESSION_', 'HERMES_CRON_')) or key in ('HERMES_PROFILE', 'HERMES_TURN_AUTHOR', 'TERMINAL_CWD', 'HERMES_TUI'):
                self.assertNotIn(key, env, key)


class NudgeTextTest(unittest.TestCase):
    def test_approval_reads_like_a_dm_from_the_authority(self):
        text = wake.nudge_text({'id': 'abc', 'status': 'approved', 'reason': 'Bounded read.'}, 'default')
        self.assertTrue(text.startswith('Message from 🤖 hermes (@hermes): Permission abc approved. Bounded read.'))
        self.assertIn('Retry the exact same action now', text)

    def test_tool_scope_approval_says_no_more_asking(self):
        text = wake.nudge_text({'id': 'abc', 'status': 'approved', 'reason': '', 'scope': 'tool', 'tool': 'terminal'}, 'default')
        self.assertIn('approved for the whole conversation', text)
        self.assertIn('terminal', text)

    def test_denial_tells_the_bot_not_to_retry(self):
        text = wake.nudge_text({'id': 'abc', 'status': 'denied', 'reason': ''}, 'clove')
        self.assertTrue(text.startswith('Message from 🤖 clove (@clove): Permission abc denied.'))
        self.assertIn('Do not retry', text)

    def test_other_states_produce_nothing(self):
        self.assertIsNone(wake.nudge_text({'id': 'abc', 'status': 'needs_user', 'reason': ''}, 'default'))


if __name__ == '__main__':
    unittest.main()
