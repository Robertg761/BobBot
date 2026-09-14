# Changelog

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
