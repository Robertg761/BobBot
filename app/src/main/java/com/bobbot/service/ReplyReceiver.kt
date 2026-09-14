package com.bobbot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.bobbot.data.repo.BotNames
import com.bobbot.data.repo.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Reply" action on a bot-message notification: resumes that session over the
 * gateway socket (the link service keeps it warm) and submits the text, like typing it in the chat.
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var prefs: com.bobbot.data.prefs.AppPrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "default"
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (text.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { BotNames.seed(prefs.currentBotNames()); BotNames.setNicknames(prefs.currentNicknames()) }
            // A broadcast receiver has about ten seconds; past that Android reports the app as not responding.
            val ok = runCatching {
                kotlinx.coroutines.withTimeout(8_000) {
                    val live = chat.resumeSession(sessionId, profile)
                    chat.send(live, text)
                }
            }.onFailure { Log.w(TAG, "reply failed", it) }.isSuccess
            notifier.replied(id, profile, ok, text)
            pending.finish()
        }
    }

    companion object {
        const val ACTION_REPLY = "com.bobbot.service.REPLY"
        const val KEY_TEXT = "reply_text"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TAG = "ReplyReceiver"
    }
}
