package com.bobbot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bobbot.ui.AppRoot
import com.bobbot.ui.share.SharePayload
import com.bobbot.ui.theme.BobBotTheme
import dagger.hilt.android.AndroidEntryPoint

/** A request to open a specific chat, delivered by a notification tap, a share, or a deep link. */
data class LaunchRequest(
    val sessionId: String?,
    val profile: String?,
    val authReturn: Boolean,
    val team: Boolean = false,
    /** Text and images another app handed us through the share sheet. */
    val share: SharePayload? = null,
    /** Dashboard address from a bobbot://pair QR code. */
    val pair: String? = null,
    val nonce: Long = System.nanoTime(),
)

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
        parseShare(i)?.let { return LaunchRequest(null, null, authReturn = false, share = it) }
        val data = i.data
        if (data != null && data.scheme == "bobbot") {
            // bobbot://pair?url=… comes from the dashboard's QR code; every other host is the auth return.
            if (data.host == "pair") {
                val url = data.getQueryParameter("url")?.trim().orEmpty()
                return if (url.isEmpty()) null else LaunchRequest(null, null, authReturn = false, pair = url)
            }
            return LaunchRequest(null, null, authReturn = true)
        }
        val sid = i.getStringExtra("open_session")
        val prof = i.getStringExtra("open_profile")
        if (i.getBooleanExtra("open_team", false)) return LaunchRequest(null, null, authReturn = false, team = true)
        if (sid != null || prof != null) return LaunchRequest(sid, prof, authReturn = false)
        return null
    }

    /**
     * A share from another app. The sender's read grant on the images lasts as long as this task,
     * which is all we need: the chat encodes them straight away and nothing is written down.
     */
    private fun parseShare(i: Intent): SharePayload? {
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        // Only images we were actually granted are readable; a sender that forgot the flag gets dropped
        // rather than blowing up in the content resolver later.
        val readable = if (i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) uris else emptyList()
        val text = listOfNotNull(
            i.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim()?.takeIf { it.isNotBlank() },
            i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotBlank() },
        ).distinct().joinToString("\n").takeIf { it.isNotBlank() }
        if (text == null && readable.isEmpty()) return null
        return SharePayload(text = text, imageUris = readable.take(SharePayload.MAX_IMAGES))
    }
}
