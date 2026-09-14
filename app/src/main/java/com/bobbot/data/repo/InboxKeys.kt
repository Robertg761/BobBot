package com.bobbot.data.repo

/** Stable keys for per-conversation local state (last opened, unread), shared by the inbox and chat screens. */
object InboxKeys {
    fun bot(profile: String) = "bot:$profile"
    fun room(roomId: String) = "room:$roomId"
}
