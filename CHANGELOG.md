# Changelog

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
