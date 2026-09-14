package com.bobbot.ui.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** Text and images another app handed BobBot through the share sheet. */
data class SharePayload(val text: String? = null, val imageUris: List<Uri> = emptyList()) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && imageUris.isEmpty()

    /** One line for the picker: the shared text, or how many images are coming. */
    val summary: String
        get() = text?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() }
            ?: if (imageUris.size == 1) "1 image" else "${imageUris.size} images"

    companion object {
        /** Hermes takes attachments one frame at a time; four photos is already a slow send. */
        const val MAX_IMAGES = 4
    }
}

/**
 * A share waiting for a bot's chat to pick it up. The picker leaves the payload here, the chat
 * takes it when it opens, fills the composer and attaches the images, and the user reviews and
 * sends. Nothing is persisted: a share the user backs out of dies with the process.
 */
@Singleton
class ShareInbox @Inject constructor() {
    private val _pending = MutableStateFlow<SharePayload?>(null)
    val pending: StateFlow<SharePayload?> = _pending

    fun offer(payload: SharePayload) { _pending.value = payload.takeIf { !it.isEmpty } }

    /** Hands the payload over exactly once, so two chats can never both claim it. */
    fun take(): SharePayload? = _pending.getAndUpdate { null }
}
