import importlib.util
import tempfile
import unittest
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location('team_store', Path(__file__).parents[1] / 'bobbot-team' / 'store.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PermissionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = module.Store(Path(self.temp.name) / 'permissions.db')

    def request(self, args=None, profile='research', session='s1'):
        return self.store.gate(profile, session, 'terminal', args or {'command': 'pwd'})

    def test_exact_action_and_profile_are_required(self):
        row = self.request()
        self.store.decide(row['id'], 'approved', 'Within assignment', 'default')
        self.assertIsNotNone(self.request({'command': 'rm -rf project'}))
        self.assertIsNotNone(self.request(profile='writer'))
        self.assertIsNone(self.request())
        self.assertIsNotNone(self.request())

    def test_specialist_cannot_approve_self(self):
        with self.assertRaises(PermissionError):
            self.store.decide(self.request()['id'], 'approved', 'Trust me', 'research')

    def test_escalated_request_requires_human(self):
        row = self.request()
        self.store.decide(row['id'], 'needs_user', 'External message', 'default')
        with self.assertRaises(ValueError):
            self.store.decide(row['id'], 'approved', 'Changed mind', 'default')
        self.store.decide(row['id'], 'approved', 'Reviewed', 'you', human=True)
        self.assertIsNone(self.request())

    def test_concurrent_consumers_cannot_reuse_approval(self):
        row = self.request()
        self.store.decide(row['id'], 'approved', 'Reviewed', 'default')
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.request(), range(4)))
        self.assertEqual(sum(r is None for r in results), 1)

    def test_pending_requests_are_deduplicated_and_persist(self):
        ident = self.request()['id']
        self.assertEqual(self.request()['id'], ident)
        self.assertEqual(module.Store(self.store.path).get(ident)['status'], 'pending')

    def test_expired_approval_cannot_execute(self):
        row = self.request()
        self.store.decide(row['id'], 'approved', 'Reviewed', 'default')
        with self.store.connection() as db:
            db.execute('UPDATE requests SET expires=0')
        self.assertIsNotNone(self.request())

    def test_decisions_are_immutable(self):
        row = self.request()
        self.store.decide(row['id'], 'denied', 'Outside scope', 'default')
        with self.assertRaises(ValueError):
            self.store.decide(row['id'], 'approved', 'Try again', 'default')
        self.assertEqual(self.request()['status'], 'denied')


if __name__ == '__main__':
    unittest.main()
