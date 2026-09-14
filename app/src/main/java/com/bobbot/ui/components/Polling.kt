package com.bobbot.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Run [block] every [intervalMs] while the screen is started, and stop while it is in the
 * background or off the back stack. Failures inside [block] are swallowed so one bad poll
 * never ends the loop or the process.
 */
@Composable
fun PollWhileStarted(key: Any?, intervalMs: Long, immediate: Boolean = true, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(key, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!immediate) delay(intervalMs)
            while (true) {
                try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
                delay(intervalMs)
            }
        }
    }
}
