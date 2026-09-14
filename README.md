# BobBot

A native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It talks to the Hermes **dashboard backend** (the FastAPI server started by `hermes dashboard`, port 9119) and turns every Hermes profile into a bot you can chat with from your phone.

Dark theme only. Kotlin, Jetpack Compose, Material 3.

## What it does

BobBot is laid out like a messages app, in the spirit of xAI's Grok Bot: bots are the main objects, not chat sessions. You open the app and see one conversation per bot.

- **First-run setup**: server address → sign in through the dashboard's native PKCE flow (system browser, no password typed in the app) → optional push notifications. Loopback-bound dashboards can use a static session token instead.
- **Messages (home)**: every bot is one row with its avatar, name, last message and time, plus group conversations in the same list. Rows sort pinned-first, then by activity. A dot on the avatar shows a bot that is working (amber, including server-side workers) or waiting on you (red); a bold row with a blue dot means it has activity you have not read. Tap a row to continue that bot's ongoing conversation. Long-press for pin, bot profile, or a separate task chat. Search covers bot names and every past chat.
- **One conversation per bot, shared with Hermes**: a bot's ongoing conversation is Hermes' own canonical "Bot Chat" for that profile, the same hidden session the Hermes desktop app and `bot-chat:` cron delivery use. Read state is Hermes' per-session watermark, so opening a chat on the phone clears it on the desktop too. Nothing about which chat is "the" chat lives on the phone.
- **+ button**: new bot (3-step wizard: identity + persona, model, review) or new group (two to six bots). The ⋮ menu holds Bot network, Automations and Settings.
- **Chat**: iMessage-style bubbles, streaming replies with a typing indicator, markdown, a collapsible thought process, tool calls as quiet activity lines, image attachments, and approval / clarify / sudo prompts that arrive inline in the transcript. Drafts survive leaving the chat. Tap the header for the bot's profile; the ⋮ menu switches the model or reasoning effort for this chat, starts a task chat, or renames a task chat.
- **Bots that talk to each other**: Hermes' Bot Mode gives every bot's chat a roster of the other bots and a `message_agent` tool; replies come back as notifications that wake the sender. BobBot turns it on from the inbox banner, for every bot it creates, and per bot from its profile (with the one-line role others see). Messages from another bot show as that bot's bubble. Open board hand-offs sit as chips above the chat.
- **Your main bot can hire**: with the team extension installed, the authority bot has `create_bot`, `configure_bot` and `list_bots`, so "Clove, make me a research bot called Steve" creates the profile, its persona, its model, enrols it for teammate messaging and team review, and it can be messaged at once.
- **Bot profile**: persona (SOUL.md), description, model, skills, teammate messaging and role line, this bot's automations, task chats (one-off jobs kept apart from the ongoing conversation; older BobBot direct chats from before 1.2.0 appear here), rename / display name, delete. Every Hermes profile is a bot. A "Grok bot" is just a bot whose model is a Grok model on the xAI provider.
- **Groups**: choose two to six bots. Hermes hosts the conversation, keeps its history, and continues accepted work when the phone disconnects. Messages show each bot's avatar and name; approval and retry requests arrive as cards; stop from the header.
- **Bot network**: the kanban board is Hermes' bot-to-bot bus. It renders every task hand-off and comment as "**Bot A → Bot B**" with both avatars, so it is always clear when bots talk to each other. You can also ask a bot to do something on another bot's behalf.
- **Automations**: Hermes cron jobs, which are how bots reach out proactively. Create, edit, pause, run now, inspect runs. Deliver to `ntfy` to get a push on this phone.
- **Notifications**: a foreground "link" service subscribes to a private ntfy topic (which setup registers with Hermes' ntfy channel), watches automations and the board, and posts a notification when a reply finishes while the app is in the background. Tapping it opens that conversation.
- **Settings / System**: connection, identity, notification toggles, ntfy config with a test button, server status, host stats, usage, gateway restart, logs, models (global default, auxiliary slots, MoA preset).

## Requirements on the computer

- Hermes Agent 0.21.2+ with the dashboard running and reachable from the phone, e.g. `hermes dashboard --host 0.0.0.0 --port 9119`. To reach it from any network, put a Cloudflare tunnel (or another reverse proxy) in front of it and give BobBot the `https://` hostname; the dashboard's own sign-in still applies. A non-loopback bind requires an auth provider (Nous OAuth is what this app expects); the app discovers `auth_flows` from `/api/status` and needs `native_pkce`.
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
ui           setup, inbox (home), chat, groups, bots, models, board, team, automations, settings, system
```

Notes on the protocol that shaped the design:

- Chat has no REST send endpoint; everything is JSON-RPC over `/api/ws`. Each socket needs a fresh single-use ticket from `POST /api/auth/ws-ticket` (30 s TTL).
- `session_id` from `session.create` is an ephemeral live handle; `stored_session_id` is the durable id used for resume and for the Chats list.
- `thinking.delta` is a spinner label, not reasoning; `reasoning.delta` is the real thing.
- Group conversations use the native `groups.*` RPC methods. Kanban handles persistent assignments and task reviews.
- Bot Mode is gated on `ui_meta.hermes-bots` in a profile's profile.yaml (the Hermes desktop app writes it). BobBot writes the same block through `profiles.configure` (`title` is the role line); the team extension writes it directly when it creates a bot. A teammate DM lands in a Bot Chat as user text prefixed `Message from 🤖 name (@name):`; the reply comes back as a background-process completion whose command is `hermes -p <name> chat … "Bot Chat"`. Both render as that bot.
- The inbox is `profiles.list` with `include_sessions`: each profile row carries `canonical_session` (the Bot Chat: id, live compression tip, preview, activity) and `worker_session` (the newest kanban / sub-agent worker, heartbeating while it runs). `GET /api/sessions/{id}` adds `last_read_at` and `pinned`; `PATCH /api/sessions/{id}` with `unread: false` marks read. The chat itself opens through `session.list` with `title: "Bot Chat"` (exact-title lookup that includes hidden rows) and, when absent, `session.create` with `title: "Bot Chat"` and `hidden: true`.
- Proactive pushes use automations and the `cronjob` tool's delivery routing to the configured ntfy topic.

## Clove and the team

Install the Hermes extension using [server/README.md](server/README.md). In BobBot,
open Network → Permissions to select the authority profile, configure existing
bots, and review requests. The default authority is `default`, whose current persona
is Clove. New profiles created in BobBot receive the extension when it is installed
on the connected server.

Specialists submit completed work to the authority for review. Tool actions outside
the extension's list of read and coordination tools need a permission. Clove can allow
the action once, allow that tool for the rest of the conversation, deny it, or send the
decision to Robert; in BobBot the bot's chat and the Team & permissions screen offer the
same three choices. Hermes' own approval checks still apply. The decision is delivered
back into the bot's chat as a message from Clove and the bot retries by itself; board
tasks wait on durable review dependencies and resume on the server.

Group conversations and individual tasks have separate context. Assignments should
include the relevant details and attach or link their results. Groups are bounded
by Hermes' discussion policy; they are not endless autonomous conversations.
