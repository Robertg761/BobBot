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
        row, _expired = self.store.gate(profile, session, 'terminal', args or {'command': 'pwd'})
        return row

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

    def test_tool_scope_covers_any_arguments_and_is_not_used_up(self):
        row = self.request({'command': 'ls'})
        self.store.decide(row['id'], 'approved', 'Read-only job', 'default', scope='tool')
        self.assertIsNone(self.request({'command': 'cat notes.md'}))
        self.assertIsNone(self.request({'command': 'ls'}))
        self.assertIsNotNone(self.request({'command': 'ls'}, profile='writer'))
        self.assertIsNotNone(self.request({'command': 'ls'}, session='s2'))
        self.assertIsNotNone(self.store.gate('research', 's1', 'write_file', {'path': 'x'})[0])

    def test_tool_scope_only_applies_to_approvals_and_expires(self):
        row = self.request()
        self.store.decide(row['id'], 'denied', 'No', 'default', scope='tool')
        self.assertEqual(self.store.get(row['id'])['scope'], 'exact')
        with self.assertRaises(ValueError):
            self.store.decide(self.request({'command': 'x'})['id'], 'approved', 'r', 'default', scope='forever')
        row = self.request({'command': 'y'})
        self.store.decide(row['id'], 'approved', 'r', 'default', scope='tool')
        with self.store.connection() as db:
            db.execute('UPDATE requests SET expires=0')
        self.assertIsNotNone(self.request({'command': 'z'}))

    def test_undecided_request_expires_into_a_fresh_one_and_reports_the_old(self):
        old = self.request()
        self.store.routed(old['id'], 'review-task-1')
        with self.store.connection() as db:
            db.execute('UPDATE requests SET expires=0')
        row, expired = self.store.gate('research', 's1', 'terminal', {'command': 'pwd'})
        self.assertNotEqual(row['id'], old['id'])
        self.assertEqual(row['status'], 'pending')
        self.assertEqual([e['id'] for e in expired], [old['id']])
        self.assertEqual(expired[0]['review_task'], 'review-task-1')
        self.assertEqual(self.store.get(old['id'])['status'], 'expired')
        with self.assertRaises(ValueError):
            self.store.decide(old['id'], 'approved', 'Too late', 'default')

    def test_denials_outlive_the_pending_window(self):
        row = self.request()
        self.store.decide(row['id'], 'denied', 'No', 'default')
        self.assertGreater(self.store.get(row['id'])['expires'], row['expires'] + 24 * 3600)
        self.assertEqual(self.request()['status'], 'denied')

    def test_schema_migration_tolerates_concurrent_openers(self):
        import sqlite3
        path = Path(self.temp.name) / 'old.db'
        db = sqlite3.connect(path)
        db.executescript('''
            CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE requests (
                id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, profile TEXT NOT NULL,
                session TEXT NOT NULL, tool TEXT NOT NULL, args TEXT NOT NULL,
                task TEXT NOT NULL, board TEXT NOT NULL, status TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '', reviewer TEXT NOT NULL DEFAULT '',
                created REAL NOT NULL, expires REAL NOT NULL, review_task TEXT);''')
        db.close()
        with ThreadPoolExecutor(max_workers=8) as pool:
            stores = list(pool.map(lambda _: module.Store(path), range(8)))
        self.assertEqual(len(stores), 8)
        self.assertIsNotNone(stores[0].gate('research', 's1', 'terminal', {'command': 'pwd'})[0])

    def test_decisions_are_immutable(self):
        row = self.request()
        self.store.decide(row['id'], 'denied', 'Outside scope', 'default')
        with self.assertRaises(ValueError):
            self.store.decide(row['id'], 'approved', 'Try again', 'default')
        self.assertEqual(self.request()['status'], 'denied')


if __name__ == '__main__':
    unittest.main()
