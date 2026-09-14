"""Durable, exact-action decisions. No Hermes imports, so the contract is testable."""
import contextlib
import hashlib
import json
import sqlite3
import time
import uuid


class Store:
    def __init__(self, path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS requests (
                    id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, profile TEXT NOT NULL,
                    session TEXT NOT NULL, tool TEXT NOT NULL, args TEXT NOT NULL,
                    task TEXT NOT NULL, board TEXT NOT NULL, status TEXT NOT NULL,
                    reason TEXT NOT NULL DEFAULT '', reviewer TEXT NOT NULL DEFAULT '',
                    created REAL NOT NULL, expires REAL NOT NULL, review_task TEXT);
                CREATE INDEX IF NOT EXISTS by_fingerprint ON requests(fingerprint, created);
            ''')
            # Older stores predate decision scopes. 'exact' = this tool with these arguments, once.
            # 'tool' = this tool, any arguments, for the rest of that conversation or task.
            # Several processes may open the store at once; whichever adds the column first wins.
            if 'scope' not in [r[1] for r in db.execute('PRAGMA table_info(requests)')]:
                try:
                    db.execute("ALTER TABLE requests ADD COLUMN scope TEXT NOT NULL DEFAULT 'exact'")
                except sqlite3.OperationalError as exc:
                    if 'duplicate column' not in str(exc):
                        raise

    @contextlib.contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            yield db
        finally:
            db.close()

    def settings(self):
        with self.connection() as db:
            row = db.execute("SELECT value FROM settings WHERE key='team'").fetchone()
        return json.loads(row[0]) if row else {"authority": "default", "enabled": True}

    def configure(self, authority, enabled):
        with self.connection() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES ('team', ?)",
                       (json.dumps({"authority": authority, "enabled": enabled}),))

    # A pending request waits this long for a decision; a denial stays on record for a week so the
    # same action is not quietly re-asked an hour later.
    PENDING_SECONDS = 3600
    DENIED_SECONDS = 7 * 24 * 3600

    def gate(self, profile, session, tool, args, task='', board='default'):
        """Returns (row, expired): ``row`` is None when the action may run, else the request blocking it;
        ``expired`` lists undecided requests that timed out and whose review cards should be closed."""
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        fingerprint = hashlib.sha256(json.dumps([profile, session, tool, encoded, task, board]).encode()).hexdigest()
        now = time.time()
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            # A standing grant for this tool in this conversation or task covers any arguments and is not used up.
            grant = db.execute(
                "SELECT id FROM requests WHERE profile=? AND session=? AND task=? AND tool=? AND scope='tool' AND status='approved' AND expires>? LIMIT 1",
                (profile, session, task, tool, now)).fetchone()
            row = db.execute('SELECT * FROM requests WHERE fingerprint=? ORDER BY created DESC LIMIT 1', (fingerprint,)).fetchone()
            if grant:
                db.commit()
                return None, []
            if row and row['status'] == 'approved' and row['expires'] > now:
                db.execute("UPDATE requests SET status='consumed' WHERE id=?", (row['id'],))
                db.commit()
                return None, []
            if row and row['status'] in ('pending', 'needs_user', 'denied') and row['expires'] > now:
                db.commit()
                return dict(row), []
            expired = []
            if row and row['status'] in ('pending', 'needs_user'):
                # Nobody decided in time. Close it out so the reviewer's card can go and a fresh request replaces it.
                db.execute("UPDATE requests SET status='expired' WHERE id=?", (row['id'],))
                expired.append(dict(row))
            ident = str(uuid.uuid4())
            db.execute('INSERT INTO requests(id,fingerprint,profile,session,tool,args,task,board,status,created,expires) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
                       (ident, fingerprint, profile, session, tool, encoded, task, board, 'pending', now, now + self.PENDING_SECONDS))
            row = dict(db.execute('SELECT * FROM requests WHERE id=?', (ident,)).fetchone())
            db.commit()
            return row, expired

    def get(self, ident):
        with self.connection() as db:
            row = db.execute('SELECT * FROM requests WHERE id=?', (ident,)).fetchone()
        if not row:
            raise ValueError('Permission request not found')
        return dict(row)

    def requests(self):
        with self.connection() as db:
            return [dict(r) for r in db.execute('SELECT * FROM requests ORDER BY created DESC LIMIT 100')]

    def for_task(self, task, *, review=False):
        column = 'review_task' if review else 'task'
        with self.connection() as db:
            return [dict(r) for r in db.execute(f'SELECT * FROM requests WHERE {column}=? ORDER BY created DESC', (task,))]

    TOOL_GRANT_SECONDS = 8 * 3600

    def decide(self, ident, choice, reason, reviewer, human=False, scope='exact'):
        if choice not in ('approved', 'denied', 'needs_user') or not reason.strip():
            raise ValueError('Choose approved, denied or needs_user and provide a reason')
        if scope not in ('exact', 'tool'):
            raise ValueError("Scope is 'exact' (this action once) or 'tool' (this tool for the rest of the conversation)")
        if scope == 'tool' and choice != 'approved':
            scope = 'exact'
        if not human and reviewer != self.settings()['authority']:
            raise PermissionError('Only the authority bot may decide')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM requests WHERE id=?', (ident,)).fetchone()
            allowed = ('pending', 'needs_user') if human else ('pending',)
            if not row or row['status'] not in allowed or row['expires'] <= time.time():
                db.rollback()
                raise ValueError('Request is no longer awaiting this reviewer')
            if scope == 'tool':
                expires = time.time() + self.TOOL_GRANT_SECONDS
            elif choice == 'denied':
                expires = time.time() + self.DENIED_SECONDS
            else:
                expires = row['expires']
            db.execute('UPDATE requests SET status=?,reason=?,reviewer=?,scope=?,expires=? WHERE id=?',
                       (choice, reason.strip(), 'you' if human else reviewer, scope, expires, ident))
            db.commit()
        return self.get(ident)

    def routed(self, ident, task):
        with self.connection() as db:
            db.execute('UPDATE requests SET review_task=? WHERE id=?', (task, ident))
