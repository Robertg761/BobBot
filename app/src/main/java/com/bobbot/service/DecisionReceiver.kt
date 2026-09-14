package com.bobbot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bobbot.data.repo.BotNames
import com.bobbot.data.repo.TeamRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides a team permission request straight from the notification, so the user never has to open
 * the app to unblock a bot. The notification is then replaced in place with the outcome.
 */
@AndroidEntryPoint
class DecisionReceiver : BroadcastReceiver() {
    @Inject lateinit var team: TeamRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var prefs: com.bobbot.data.prefs.AppPrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val scope = intent.getStringExtra(EXTRA_SCOPE) ?: "exact"
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "default"
        val tool = intent.getStringExtra(EXTRA_TOOL).orEmpty()
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, Notifier.decisionNotificationId(requestId))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { BotNames.seed(prefs.currentBotNames()); BotNames.setNicknames(prefs.currentNicknames()) }
            // A broadcast receiver has about ten seconds before Android calls the app unresponsive.
            val ok = runCatching {
                kotlinx.coroutines.withTimeout(8_000) {
                    team.decide(requestId, choice, scope, REASON)
                }
            }.onFailure { Log.w(TAG, "decide failed", it) }.isSuccess
            notifier.decided(id, profile, tool, choice, scope, ok)
            pending.finish()
        }
    }

    companion object {
        const val ACTION_DECIDE = "com.bobbot.service.DECIDE"
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val REASON = "Decided from the notification"
        private const val TAG = "DecisionReceiver"
    }
}
