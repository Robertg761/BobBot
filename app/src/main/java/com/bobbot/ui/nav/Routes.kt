package com.bobbot.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Setup : Route
    /** The inbox: one row per bot plus group conversations. */
    @Serializable data object Home : Route
    @Serializable data class Chat(val sessionId: String? = null, val profile: String? = null, val mainConversation: Boolean = false) : Route
    @Serializable data class Group(val roomId: String) : Route
    @Serializable data object NewGroup : Route
    @Serializable data class BotDetail(val name: String) : Route
    @Serializable data object NewBot : Route
    @Serializable data object Board : Route
    @Serializable data object Team : Route
    @Serializable data object Automations : Route
    @Serializable data object Models : Route
    @Serializable data object Settings : Route
    @Serializable data object System : Route
}
