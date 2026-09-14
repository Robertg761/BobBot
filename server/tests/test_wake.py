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


class NudgeTextTest(unittest.TestCase):
    def test_approval_reads_like_a_dm_from_the_authority(self):
        text = wake.nudge_text({'id': 'abc', 'status': 'approved', 'reason': 'Bounded read.'}, 'default')
        self.assertTrue(text.startswith('Message from 🤖 hermes (@hermes): Permission abc approved. Bounded read.'))
        self.assertIn('Retry the exact same action now', text)

    def test_denial_tells_the_bot_not_to_retry(self):
        text = wake.nudge_text({'id': 'abc', 'status': 'denied', 'reason': ''}, 'clove')
        self.assertTrue(text.startswith('Message from 🤖 clove (@clove): Permission abc denied.'))
        self.assertIn('Do not retry', text)

    def test_other_states_produce_nothing(self):
        self.assertIsNone(wake.nudge_text({'id': 'abc', 'status': 'needs_user', 'reason': ''}, 'default'))


if __name__ == '__main__':
    unittest.main()
