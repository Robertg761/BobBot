package com.bobbot.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes first-open so two taps cannot create two direct conversations. */
class MainConversationResolver {
    private val lock = Mutex()
    suspend fun open(
        saved: suspend () -> String?,
        live: (String) -> String?,
        latest: suspend (String) -> String?,
        resume: suspend (String) -> String,
        create: suspend () -> Pair<String, String>,
        save: suspend (String) -> Unit,
    ): String = lock.withLock {
        saved()?.let { id ->
            live(id)?.let { return@withLock it }
            // null means confirmed missing; connectivity/authentication failures propagate.
            latest(id)?.let { target ->
                val result = resume(target)
                save(target)
                return@withLock result
            }
        }
        val (result, id) = create()
        require(id.isNotBlank()) { "Hermes did not return a durable conversation ID." }
        save(id)
        result
    }
}
