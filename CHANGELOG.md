# Changelog

## [1.3.5] - 2026-09-14

### Fixed
- The expanded bot-message notification is headed with the bot's name. Android showed the app name there for a one-to-one thread; the conversation is now always titled with the bot (or "bot · task chat").

## [1.3.4] - 2026-09-14

Everything in this release comes out of a deep audit of the app and the team extension, run part by part (inbox and groups, chat and streaming, background link and notifications, the Hermes plugin).

### Fixed
- **Notifications name the bot.** Every notification carries the bot's display name (persona or nickname): completions, scheduled results, bot-to-bot hand-offs, reply confirmations, and ntfy pushes. Names are cached on the phone, so the link service names bots correctly straight after a reboot, before the app has loaded anything.
- **Messages from a bot stack as one conversation.** A bot that sends several messages updates one notification thread instead of piling up separate ones; a reply from the shade clears that thread.
- **The background link stays up on Android 15 and 16.** It now runs as a "special use" foreground service; the previous type was cut off after six hours a day. If Android refuses to promote it, it stops cleanly instead of crashing the process, and stopping the link from the notification also drops the socket when the app is closed.
- **Streaming is cheaper and steadier.** Deltas are batched about every 50 ms instead of one state update per token; long transcripts are projected off the main thread; every streamed bubble carries a timestamp so day separators and time labels stay right; a failed send removes the phantom message and restores its attachments instead of leaving it looking sent.
- **A bad event can no longer stop all chats.** One malformed frame used to end the event pump for every open conversation. Unscoped events are only attributed when there is exactly one place they can belong.
- **Reconnects no longer multiply.** The socket keeps a single reconnect loop; a second "closed" callback for the same connection is ignored. Access tokens are URL-encoded in the WebSocket handshake.
- **Photos attach without freezing.** Images are decoded, downscaled to 2048 px and JPEG-compressed off the main thread, so a raw phone photo no longer builds a tens-of-megabytes frame on the UI thread.
- Polling (inbox, group chats, team, permission cards) pauses while the app is in the background and stops on leaving a screen.
- The composer stays typeable during a socket blip; only sending waits for the connection. The rename dialog seeds from the current title. Two identical toasts in a row both show.
- Session-token installs (loopback, no auth) work from the link service and reply receiver too; the token mode is restored on first use rather than only when an activity starts.
- Request paths are encoded segment by segment; a server address with a path is rejected at setup instead of silently failing every request. Boot restarts and shade replies no longer block the main thread; a shade reply gives up after eight seconds.
- Inbox: a group with activity you have never opened counts as unread; a group with no activity yet does not. Duplicate sessions, rooms, members and tasks are filtered out before they reach a list.

### Team extension 0.3.2
- The acting profile is captured when the plugin registers, so a specialist on a thread that lost its home context can no longer be mistaken for the authority (which silently disabled review). The guidance text uses the profile Hermes hands it.
- The kill switch is checked before anything that can fail, so a broken install can always be turned off.
- Undecided requests that time out are marked expired, their review card is closed, and a fresh request replaces them; denials stay on record for a week so the same action is not quietly re-asked an hour later.
- The nudge that tells a bot its decision runs with a scrubbed environment: no kanban worker identity, no chat session, no cron delivery target from whoever decided; its stderr goes to the profile's logs.
- Review tasks target the request's own board even from inside another board's worker, and get ten minutes instead of three.
- `todo_list` and `kanban_attachments` pass without review (the old list named a toolset, so every planning call was stopped). A moved extension re-points its symlink instead of failing setup. Creating a bot no longer copies the authority's private memories; model and provider must be given together.
- A decision that landed but whose follow-up failed now returns the decision with a note instead of an error.

## [1.3.3] - 2026-09-14

### Fixed
- **Crash on opening a chat that contains a table or code block** (introduced in 1.3.2 by the bubble-hugging change). Bubbles still hug plain prose; anything with tables, code, lists or headings takes the full width. If you are on 1.3.2, update.

## [1.3.2] - 2026-09-14

### Changed
- **Permissions you can actually act on.** A bot that is stopped on a team permission shows a card at the top of its chat: who wants which tool, the command or path, what your main bot said, and Allow / Allow this tool here / Deny. The inbox row shows "Needs your decision". Team & permissions is rebuilt around the same cards: what needs you, what the authority is still reviewing, the team, and history folded away.
- **Decisions have a scope.** "Allow" is this action once; "Allow &lt;tool&gt; here" lets the bot use that tool for the rest of the conversation (8 hours), so it is not stopped again for every read. The authority bot has the same choice and is told to use it for read-only or clearly repeated work.
- **Bots pick up where they left off.** A decision is delivered into the bot's chat as a message from the authority and it retries on its own; pure lookups (tool search, tool descriptions, listing jobs) no longer need review at all. Requires team extension 0.3.0.
- **Scheduled results come back to the app.** New automations deliver to the chosen bot's chat by default (the "… chat (in BobBot)" targets are listed first), and a result delivered into a bot's chat notifies you like a message from that bot unless you already have the chat open. Bots are told to use bot-chat delivery for anything meant for you and never to claim results can't reach the chat.
- Hermes' own tool-approval prompts read "Allow", "Allow in this chat", "Always allow", "Deny".
- Team & permissions is in the inbox's ⋮ menu, with a count when something needs you; a decision notification opens the bot's chat, where the card is. Your main bot's chat shows how many reviews it is sitting on, with a link to them.

### Fixed
- The keyboard capitalises sentences in chats, group chats, and the description, persona, role and group-name fields. The message box gave the keyboard no hint before, so it stayed lowercase.
- The send and stop buttons sit inside the message bar with room around them instead of being jammed into its edge.
- Bot bubbles hug their text like your own do, instead of always stretching to the maximum width.

## [1.3.1] - 2026-09-14

### Changed
- **Far fewer notifications.** Bot-to-bot board traffic is off by default and, when on, only reports a bot finishing or getting stuck on work another bot gave it. The team extension's permission-review tasks never notify. New "Decisions for you" notifications fire only when your main bot escalates a specialist's request to you, and tap through to Team & permissions.
- The background link notification is now minimum importance: no status-bar icon, no sound, collapsed at the bottom of the shade. Android requires it while BobBot listens in the background; stopping the link from Settings removes it.

### Fixed
- Streaming replies no longer jitter. The chat snaps to the newest text without animation while a reply streams, animates only when a new message arrives, and stops following the moment you scroll up, until you return to the bottom. Group chats behave the same way.
- An `https://` server address no longer gets port 9119 appended. A tunnel or reverse proxy on 443 (for example a Cloudflare tunnel in front of the dashboard) now works by typing just the hostname. Plain `http://` addresses and bare hosts still default to 9119.

## [1.3.0] - 2026-09-14

### Added
- **Bots can message each other.** Hermes' teammate messaging (Bot Mode) is switched on for every bot from the inbox banner, automatically for bots BobBot creates, and per bot from its profile, where you can also set the one-line role other bots see. Each bot's chat then carries the roster and the `message_agent` tool.
- **Teammate messages look like messages.** A DM from another bot, and a bot's reply to a DM this bot sent, show as that bot's bubble with its avatar and name instead of as your text or a system line. Inbox previews read "Steve: …".
- **Clove can create bots by talking.** The team extension adds `create_bot` (name, role, persona, model; cloned from the authority, enrolled in teammate messaging and team review), `configure_bot`, and `list_bots`. Bots messaging each other no longer needs a permission review.
- **Hand-offs in the chat.** Open board tasks a bot is assigned or created appear as chips above its conversation; tap one for the network. Board notifications open the assignee's chat.
- **Groups**: @mention completion for members while you type.
- **Reply from the notification shade**; the reply goes into the same conversation.
- Day separators and times in chats.

### Changed
- The inbox refreshes from gateway change events and asks the server only once a minute otherwise.
- Welcome screen copy describes the inbox layout.
- Team extension version 0.2.0. Reinstall it with `server/install.py` to pick up the new tools.

## [1.2.0] - 2026-09-14

### Changed
- **BobBot is now a messages app.** The tab bar is gone. Home is an inbox with one row per bot: avatar, name, last message, time, pin, and an unread marker when a bot replied while you were away. A dot on the avatar shows a bot that is working or waiting on you. Tap a bot to continue its ongoing conversation; long-press for pin, profile, or a separate task chat. Group conversations live in the same list. Modeled on how Grok Bot treats bots, not sessions, as the main objects.
- **Chat looks like messaging**: blue bubbles for you, grey for the bot, tighter spacing inside a run of messages, a typing indicator while the bot works, and tool calls as quiet activity lines. Approval, clarify and sudo prompts arrive inline in the transcript instead of as dialogs. Drafts survive leaving a chat. Model and reasoning switching moved to the ⋮ menu; the header shows the current model and status, and tapping it opens the bot's profile.
- **Groups** got a real chat screen: bubbles with each bot's avatar and name, cards for approvals and retries, a stop button in the header, and a "New group" picker from the + button.
- **Bot profile** now lists the bot's automations and calls its extra chats "task chats", with a shortcut to start one. Bot network, Automations and Settings open from the ⋮ menu on the inbox and have back buttons.
- **A bot's conversation is now Hermes' own "Bot Chat"** for that profile, shared with the Hermes desktop app and with `bot-chat:` cron delivery, instead of a session id remembered on the phone. Unread state is Hermes' read watermark, so it stays in step across devices, and a bot shows as working when a server-side worker is running for it even if the phone started nothing. Direct chats BobBot created before this release stay available under the bot's task chats.

### Removed
- The Bots and Chats tabs. Every bot is an inbox row, and past task chats are reachable from a bot's profile or from inbox search.
- The client-side relay feature and its notification toggle. Hermes-hosted groups replace it.

## [1.1.2] - 2026-09-14

### Fixed
- Persona templates no longer replace a bot's chosen name with "Persona". New personas include the bot's name, and existing generic headings fall back to the profile name.
- Bot cards no longer show the messaging gateway as a chat availability indicator. Direct chats start on demand.

## [1.1.1] - 2026-09-14

### Fixed
- Creating a bot no longer fails with HTTP 422 on Hermes 0.21.2. Cloned bots retain their inherited skills.

## [1.1.0] - 2026-09-14

### Added
- Persistent group conversations hosted by Hermes, including history, interjections, stop, retry, and permission responses. Requires Hermes 0.21.2.
- Team & permissions screen for selecting the authority bot and reviewing exact-action requests.
- Optional Hermes team extension that routes specialist actions to Clove, escalates decisions to Robert, and requires authority review before completing team tasks.

### Changed
- Tapping a bot opens its ongoing direct conversation. Settings have a separate button; new task chats remain available from Chats.
- New bots get a distinct persona and are configured for team work when the extension is installed.

### Fixed
- Persona-save failures are reported instead of silently completing bot creation.
- Task creation sends the details using Hermes' correct API field. The board includes ready, running, review and triage states.
- Activity comments are matched by comment ID instead of repeating an author's latest comment.

## [1.0.3] - 2026-09-14

### Changed
- **Bots show their persona name**: the default profile can't be renamed in Hermes (it *is* the ~/.hermes directory), so BobBot now shows each bot's persona name from the top of its SOUL.md, e.g. "Clove" instead of "default", across chats, avatars, the bot list, the network view, and relays.
- **Display name**: the default bot's Rename action is now "Display name", a local nickname that overrides the persona heading. Other bots keep the real profile rename.

## [1.0.2] - 2026-09-14

### Fixed
- **Automation notifications were noisy**: every run of a scheduled job produced a "finished" notification, including silent runs like the email monitor finding nothing. BobBot now reads the run's actual reply, stays quiet for `[SILENT]` or empty runs, and skips jobs that already deliver to Telegram, ntfy, or another channel. Failures still notify.
- **Run history** in Automations now shows each run's real reply text instead of a blank preview.

## [1.0.1] - 2026-09-14

### Changed
- **New app icon**: two chat bubbles with a typing indicator, on the app's dark palette. Replaces the robot face. Includes a themed (monochrome) icon for Android 13+ launchers and a matching notification glyph.

## [1.0.0] - 2026-09-13

### Added
- **First release**: BobBot, a native Android client for Hermes Agent's dashboard backend.
- **Setup flow**: server address, native PKCE sign-in through the system browser (or a session token for loopback dashboards), and push-notification opt-in.
- **Chats**: streaming replies over the dashboard WebSocket, markdown rendering, reasoning disclosure, tool-call cards, image attachments, tool approval / clarify / sudo prompts, stop, rename, pin, archive, and search across all bots.
- **Model switching**: per-chat model and reasoning-effort chips, a bot-default option, and a Models screen for the global default, auxiliary slots, and MoA preset.
- **Bots**: every Hermes profile as a bot, with a 3-step creation wizard, persona (SOUL.md) editor, description, model, and skills.
- **Bot network**: kanban hand-offs and comments rendered as "Bot A → Bot B", plus "Ask a bot" tasks.
- **Relay**: live bot-to-bot conversations driven from the phone, with interjections.
- **Automations**: Hermes cron jobs with create, edit, pause, run now, and run history.
- **Notifications**: a background link service that subscribes to a private ntfy topic, watches automations and the board, and notifies when a reply finishes in the background.
- **In-app updates**: checks GitHub Releases, downloads the signed APK, and installs it.
