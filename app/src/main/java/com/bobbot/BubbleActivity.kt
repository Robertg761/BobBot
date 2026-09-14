package com.bobbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bobbot.ui.chat.ChatScreen
import com.bobbot.ui.theme.BobBotTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * One bot's conversation inside an Android chat bubble. Bubbles need a standard-launch,
 * embeddable activity, which the single-task main activity cannot be; this one only ever shows a
 * chat and hands everything else to the full app.
 */
@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "default"
        setContent {
            BobBotTheme {
                ChatScreen(
                    sessionId = null, profile = profile, mainConversation = true,
                    onBack = { finish() },
                    onOpenProfile = { openFull(it) },
                    onNewTaskChat = { openFull(it) },
                    onOpenNetwork = { openFull(profile) },
                    onOpenTeam = { openFull(profile) },
                )
            }
        }
    }

    private fun openFull(profile: String) {
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .putExtra("open_profile", profile)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val EXTRA_PROFILE = "open_profile"
    }
}
