package com.bobbot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bobbot.ui.AppRoot
import com.bobbot.ui.theme.BobBotTheme
import dagger.hilt.android.AndroidEntryPoint

/** A request to open a specific chat, delivered by a notification tap or the auth deep link. */
data class LaunchRequest(val sessionId: String?, val profile: String?, val authReturn: Boolean, val team: Boolean = false, val nonce: Long = System.nanoTime())

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var launch by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        launch = parse(intent)
        setContent {
            BobBotTheme { AppRoot(launch = launch, onLaunchConsumed = { launch = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parse(intent)?.let { launch = it }
    }

    private fun parse(i: Intent?): LaunchRequest? {
        i ?: return null
        val data = i.data
        if (data != null && data.scheme == "bobbot") return LaunchRequest(null, null, authReturn = true)
        val sid = i.getStringExtra("open_session")
        val prof = i.getStringExtra("open_profile")
        if (i.getBooleanExtra("open_team", false)) return LaunchRequest(null, null, authReturn = false, team = true)
        if (sid != null || prof != null) return LaunchRequest(sid, prof, authReturn = false)
        return null
    }
}
