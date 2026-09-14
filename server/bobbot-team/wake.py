"""After a decision, tell the specialist so it retries on its own.

A blocked tool call ends the specialist's turn; Hermes never replays it. Without a nudge the bot
sits there saying it is "waiting for approval" until a human remembers to poke it. So when the
authority (or Robert) decides a direct-conversation request, the decision is delivered into the
specialist's Bot Chat as a teammate message, exactly the way ``message_agent`` delivers one, and
the bot's next turn retries the exact action (which the store then consumes).

Pure text helpers first (unit-tested without Hermes), then the Hermes-backed delivery.
"""
import logging
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

log = logging.getLogger('bobbot-team')

# Tools that only read: never worth a review, whatever the arguments.
READ_ONLY_TOOLS = frozenset({'tool_search', 'tool_describe'})
# Read-only actions of tools that also have writing actions.
READ_ONLY_ACTIONS = {'cronjob_manage': frozenset({'list'})}


def is_read_only(tool, args):
    if tool in READ_ONLY_TOOLS:
        return True
    actions = READ_ONLY_ACTIONS.get(tool)
    if actions is None:
        return False
    return str((args or {}).get('action', '')).strip().lower() in actions


def handle_of(profile):
    """Hermes aliases the default profile as @hermes in rosters and DMs."""
    return 'hermes' if profile == 'default' else profile


def nudge_text(row, authority):
    """The message the specialist receives. Prefixed like a Bot Mode DM so BobBot shows it as the authority speaking."""
    handle = handle_of(authority)
    prefix = f'Message from 🤖 {handle} (@{handle}): '
    reason = (row.get('reason') or '').strip()
    reason = (' ' + reason) if reason else ''
    if row['status'] == 'approved':
        body = (f"Permission {row['id']} approved.{reason} Retry the exact same action now, same tool and same arguments; "
                'the approval is valid for one hour and is used up by that retry. Then carry on with the work.')
    elif row['status'] == 'denied':
        body = (f"Permission {row['id']} denied.{reason} Do not retry that action. Find another way within your assignment, "
                'or tell Robert what you could not do and why.')
    else:
        return None
    return prefix + body


def hermes_cli():
    """The hermes launcher the delivery runner should exec; service contexts often lack PATH."""
    for candidate in (shutil.which('hermes'), str(Path(sys.executable).parent / 'hermes'), os.path.expanduser('~/.local/bin/hermes')):
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    return 'hermes'


def bot_chat_ids(profile_home):
    """The specialist's canonical Bot Chat id plus its live compression tip, or an empty set."""
    from hermes_state import SessionDB
    db_path = Path(profile_home) / 'state.db'
    if not db_path.is_file():
        return set()
    db = SessionDB(db_path=db_path, read_only=True)
    try:
        row = db.get_session_by_title('Bot Chat')
        if not row:
            return set()
        ids = {row['id']}
        try:
            ids.add(db.get_compression_tip(row['id']) or row['id'])
        except Exception:
            pass
        return ids
    finally:
        try:
            db.close()
        except Exception:
            pass


def nudge_specialist(row, authority):
    """Deliver the decision into the requesting bot's Bot Chat. Best effort: returns True when a delivery was started.

    Only direct-conversation requests that came from the Bot Chat itself qualify. A request raised in a
    task chat would, if nudged here, be retried in the wrong session and gated all over again."""
    if row.get('task') or row['status'] not in ('approved', 'denied'):
        return False
    text = nudge_text(row, authority)
    if not text:
        return False
    from hermes_cli.profiles import get_profile_dir, profile_exists
    if not profile_exists(row['profile']):
        return False
    home = get_profile_dir(row['profile'])
    if row.get('session') not in bot_chat_ids(home):
        log.info('bobbot-team: not nudging %s; request %s came from session %s, not its Bot Chat', row['profile'], row['id'], row.get('session'))
        return False
    from tools import bot_mode_dm as dm
    author = {'id': f'bot:{authority}', 'name': handle_of(authority), 'is_bot': True}
    dm_file = dm._write_dm_file(text)
    from tools.bot_relay import BOT_CHAT_TURN_ARGS
    argv = [hermes_cli(), '-p', row['profile'], *BOT_CHAT_TURN_ARGS]
    try:
        dm._admit_live_dm(home, dm_file, author)  # queues for a surface that holds the chat live (BobBot, desktop)
    except Exception as exc:
        log.info('bobbot-team: live admission skipped for %s: %s', row['profile'], exc)
    command = dm._delivery_command(argv, dm_file, stdin_file=False, profile_home=home, author=author)
    env = dict(os.environ)
    env.pop('HERMES_KANBAN_TASK', None)
    env.pop('HERMES_KANBAN_BOARD', None)
    subprocess.Popen(shlex.split(command), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, env=env)
    return True
