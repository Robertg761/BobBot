# BobBot team extension

Requires Hermes 0.21.2. This is a native Hermes plugin, not a Codex plugin.
It uses the existing dashboard authentication, Kanban dispatcher and plugin hooks.
It does not modify Hermes core.

## Install

Run with the Python interpreter in your Hermes installation:

```sh
/path/to/hermes-agent/venv/bin/python /path/to/BobBot/server/install.py
```

The installer links this directory into each existing profile, enables the plugin,
and enables `kanban` and `bobbot_team` tools for CLI/dashboard sessions. Keep the
BobBot checkout in place. Restart the Hermes dashboard and gateway afterwards.
Start new conversations to pick up the role guidance and tool schemas.

Bots created through BobBot are configured through the extension's bootstrap
endpoint. For profiles created elsewhere, rerun the installer or use Network →
Permissions → Configure in BobBot. Configure each platform separately if you also
want to coordinate through Telegram or another channel.

The default authority is the `default` profile, Clove on Robert's installation.
Changing a display name does not change its profile ID. Network → Permissions can
select a different existing authority and enable or disable team review.

## Bots creating bots

The authority profile gets three extra tools: `list_bots`, `create_bot` (name, role, optional
persona, model and provider; the profile is cloned from the authority, its SOUL.md heading is
the bot's name, it is flagged for Hermes teammate messaging and linked into this extension)
and `configure_bot`. Specialists get `list_bots` only and are told to ask the authority.
`message_agent`, Hermes' own bot-to-bot DM tool, is treated as coordination and never needs
an action review. `PUT /api/plugins/bobbot-team/profiles/{name}/teammate-messaging` turns
Bot Mode on or off for one profile, which BobBot uses as well as the gateway's
`profiles.configure`.

## Behavior and boundaries

- One approval authorizes one exact tool/arguments combination for one specialist
  and conversation or board task. It expires after one hour. Changed arguments,
  another profile, or a second use need another decision.
- The hook blocks the initial action and creates a durable review task for Clove.
  Board work waits on that task as a dependency. A direct conversation is not
  replayed: instead the decision is delivered into the specialist's Bot Chat as a
  message from the authority, and the bot retries the exact action on that turn.
  Requests raised from a task chat still need a manual retry there.
- Pure lookups never need review: `tool_search`, `tool_describe`, and
  `cronjob_manage` with `action=list`, on top of the `READ_TOOLS` set.
- Clove uses `team_permissions` and `team_decide`. `approved` and `denied` finish
  the permission review; `needs_user` parks it until Robert decides in BobBot.
- Clove's decision does not bypass Hermes' native approval checks. The policy
  instructs Clove to escalate external messages, spending, destructive work,
  credential or permission changes, and unclear scope.
- Specialist `kanban_complete` calls are blocked and directed to
  `kanban_request_review`, whose reviewer is set to the authority profile.
- The plugin enforces Hermes tool dispatch. Profiles sharing the same OS account
  are not a security sandbox against malicious code or arbitrary external clients.
  Read/coordination tools in `READ_TOOLS` do not need an action review.
- Permission arguments and decisions are stored in the default profile's
  `bobbot-team/permissions.db`. The authenticated dashboard API exposes them to the
  owner; do not treat this database as a credential store.
- Only profiles with this plugin enabled are governed. Group discussion itself
  uses Hermes' native bounded discussion policy, not a separate scheduler.

## Verification

```sh
python3 -m unittest discover -s server/tests -v
PYTHONPATH=/path/to/hermes-agent /path/to/hermes-agent/venv/bin/python server/tests/integration_hermes.py
PYTHONPATH=/path/to/hermes-agent /path/to/hermes-agent/venv/bin/python server/tests/integration_profile_create.py
hermes plugins validate server/bobbot-team
```

The integration test creates an isolated temporary Hermes home, loads the actual
plugin hook, creates real Kanban tasks, checks reviewer identity, consumes an
approval, and exercises the FastAPI routes. It makes no model calls and sends no
external messages.

## Disable or roll back

Disable team review in BobBot to stop requesting decisions while retaining history.
To remove the extension from Hermes, run `hermes -p PROFILE plugins disable
bobbot-team` for each configured profile, then restart the dashboard and gateway.
Existing permission tasks remain on the board for explicit review or cancellation.

The Hermes update performed during this work moved 0.19.0 to 0.21.2. Its full backup
is `~/.hermes/backups/pre-update-2026-09-14-004335.zip`. Use Hermes' import/restore
commands only when deliberately rolling back your state; restoring can replace
changes made since that backup.
