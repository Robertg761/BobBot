# BobBot

A native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It talks to the Hermes **dashboard backend** (the FastAPI server started by `hermes dashboard`, port 9119) and turns every Hermes profile into a bot you can chat with from your phone.

Dark theme only. Kotlin, Jetpack Compose, Material 3.

## What it does

- **First-run setup**: server address → sign in through the dashboard's native PKCE flow (system browser, no password typed in the app) → optional push notifications. Loopback-bound dashboards can use a static session token instead.
- **Chats**: streaming replies over the dashboard's JSON-RPC WebSocket (`/api/ws`), markdown rendering, reasoning disclosure, tool-call cards, image attachments, tool-approval / clarify / sudo prompts, stop, rename, pin, archive, search across all bots.
- **Model switching**: tap the model chip in any chat to switch that chat's model (or make it the bot's default). A Models screen manages the global default, auxiliary model slots, and shows the MoA preset.
- **Bots**: every Hermes profile is a bot. Create one in a 3-step wizard (identity + persona, model, review), edit its SOUL.md persona, description, model, and skills. A "Grok bot" is just a bot whose model is a Grok model on the xAI provider.
- **Bot network**: the kanban board is Hermes' bot-to-bot bus. The Network tab renders every task hand-off and comment as "**Bot A → Bot B**" with both avatars, so it is always clear when bots talk to each other. You can also ask a bot to do something on another bot's behalf.
- **Relay**: start a live conversation between two bots. BobBot opens a session with each and passes replies back and forth; you can interject at any time. Every message is labelled with sender → recipient.
- **Automations**: Hermes cron jobs, which are how bots reach out proactively. Create, edit, pause, run now, inspect runs. Deliver to `ntfy` to get a push on this phone.
- **Notifications**: a foreground "link" service subscribes to a private ntfy topic (which setup registers with Hermes' ntfy channel), watches automations and the board, and posts a notification when a reply finishes while the app is in the background.
- **Settings / System**: connection, identity, notification toggles, ntfy config with a test button, server status, host stats, usage, gateway restart, logs.

## Requirements on the computer

- Hermes Agent 0.19+ with the dashboard running and reachable from the phone, e.g. `hermes dashboard --host 0.0.0.0 --port 9119`. A non-loopback bind requires an auth provider (Nous OAuth is what this app expects); the app discovers `auth_flows` from `/api/status` and needs `native_pkce`.
- For push notifications: nothing extra. Setup enables the built-in `ntfy` messaging platform on Hermes with a generated private topic. Bots and automations that deliver to `ntfy` land on the phone.
- For the Network tab: the bundled kanban plugin (`kanban.db` present in `~/.hermes`).

## Build

```
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-temurin   # any JDK 17 works
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 9.3, Kotlin 2.4, compileSdk 37, minSdk 26.

## Releases and updates

Releases work the same way as LunarLog and Rosewater:

- **Cutting a release**: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a matching `## [x.y.z] - YYYY-MM-DD` section to `CHANGELOG.md`, and push to `main`. The Release workflow detects the version bump, runs the test/lint gate, builds a signed APK, and publishes a GitHub Release tagged `vx.y.z` with the changelog section as release notes. Pushing a `vx.y.z` tag that matches the current version works too.
- **Signing**: the workflow reads `BB_KEYSTORE_B64`, `BB_SIGNING_STORE_PASSWORD`, `BB_SIGNING_KEY_ALIAS`, and `BB_SIGNING_KEY_PASSWORD` from repository secrets. Locally, a `keystore.properties` at the repo root (gitignored) with the same keys signs `./gradlew :app:assembleRelease`.
- **In-app updates**: release builds check GitHub Releases on launch (at most every 6 hours) and offer the new APK in a bottom sheet. Settings → About has a manual "Check now" button. Debug builds never auto-check because they are signed with a different key.
- The updater needs the repository's releases to be readable without a token, so the repo must be public for auto-update to work.

## Architecture

```
core/net     HermesClient (HTTP + bearer refresh), HermesApi (typed REST), GatewaySocket (JSON-RPC over /api/ws)
core/auth    AuthManager (RFC 8252 loopback PKCE), TokenStore
data/model   Bot, SessionSummary, ModelProvider, CronJob, BoardTask, …
data/repo    ChatRepository (event stream → per-session state), Bots/Models/Automations/Board/Relay/System repositories
data/prefs   DataStore-backed settings
service      LinkService (foreground: ntfy stream + board/cron watchers + background completions), Notifier, BootReceiver
ui           setup, sessions, chat, bots, models, board, relay, automations, settings, system
```

Notes on the protocol that shaped the design:

- Chat has no REST send endpoint; everything is JSON-RPC over `/api/ws`. Each socket needs a fresh single-use ticket from `POST /api/auth/ws-ticket` (30 s TTL).
- `session_id` from `session.create` is an ephemeral live handle; `stored_session_id` is the durable id used for resume and for the Chats list.
- `thinking.delta` is a spinner label, not reasoning; `reasoning.delta` is the real thing.
- Hermes has no agent-to-agent primitive besides the kanban board, so the Relay feature runs client-side.
- `send_message` is deliberately not model-callable in Hermes, so proactive pushes come from automations (cron) and the `cronjob` tool's `deliver` parameter, routed to the ntfy topic.
