package com.bobbot.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Setup : Route
    @Serializable data object Home : Route
    @Serializable data class Chat(val sessionId: String? = null, val profile: String? = null) : Route
    @Serializable data object Sessions : Route
    @Serializable data object Bots : Route
    @Serializable data class BotDetail(val name: String) : Route
    @Serializable data object NewBot : Route
    @Serializable data object Board : Route
    @Serializable data object Relay : Route
    @Serializable data object Automations : Route
    @Serializable data object Models : Route
    @Serializable data object Settings : Route
    @Serializable data object System : Route
}
