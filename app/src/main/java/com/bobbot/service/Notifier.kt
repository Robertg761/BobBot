package com.bobbot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bobbot.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every notification BobBot posts. Three channels so the user can tune them separately:
 * bot messages (loud), the background link (silent, ongoing), bot-to-bot relay traffic (default).
 */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val ctx: Context) {

    companion object {
        const val CH_BOTS = "bots"
        const val CH_LINK = "link"
        const val CH_RELAY = "relay"

        /** Stable id of the foreground-service notification. */
        const val ID_LINK = 1001

        private const val TAG = "Notifier"
        private val seq = AtomicInteger(2000)
    }

    private val manager = NotificationManagerCompat.from(ctx)

    init {
        runCatching { createChannels() }.onFailure { Log.w(TAG, "channel setup failed", it) }
    }

    private fun createChannels() {
        val bots = NotificationChannelCompat.Builder(CH_BOTS, NotificationManager.IMPORTANCE_HIGH)
            .setName("Bot messages")
            .setDescription("Replies, automation results and anything a bot pushes to you")
            .build()
        val link = NotificationChannelCompat.Builder(CH_LINK, NotificationManager.IMPORTANCE_LOW)
            .setName("Background link")
            .setDescription("The persistent connection to your Hermes server")
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        val relay = NotificationChannelCompat.Builder(CH_RELAY, NotificationManager.IMPORTANCE_DEFAULT)
            .setName("Bot-to-bot")
            .setDescription("Board traffic between your bots")
            .build()
        manager.createNotificationChannelsCompat(listOf(bots, link, relay))
    }

    // ---- public API ----

    /** A message from a bot. Tapping it deep-links into the session it came from. */
    fun botMessage(bot: String, title: String, text: String, sessionId: String?, profile: String?) {
        val body = text.ifBlank { "(no content)" }
        val id = seq.incrementAndGet()
        val n = base(CH_BOTS)
            .setContentTitle(title.ifBlank { bot })
            .setContentText(body.lineSequence().firstOrNull()?.take(120) ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setSummaryText(bot))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(sessionId, profile))
            .apply { if (sessionId != null && profile != null) addAction(replyAction(id, sessionId, profile)) }
            .build()
        post(id, n)
    }

    /** One bot talking to another on the board. */
    fun botToBot(from: String, to: String, text: String) {
        val body = text.ifBlank { "(no content)" }
        val n = base(CH_RELAY)
            .setContentTitle("$from → $to")
            .setContentText(body.lineSequence().firstOrNull()?.take(120) ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(null, to))
            .build()
        post(seq.incrementAndGet(), n)
    }

    /** The result of a scheduled automation run. */
    fun automation(name: String, bot: String, output: String, ok: Boolean) {
        val body = output.ifBlank { if (ok) "finished" else "failed" }
        val n = base(CH_BOTS)
            .setContentTitle(if (ok) name else "$name failed")
            .setContentText(body.lineSequence().firstOrNull()?.take(120) ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setSummaryText(bot))
            .setSubText(bot)
            .setPriority(if (ok) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(null, null))
            .build()
        post(seq.incrementAndGet(), n)
    }

    /** The ongoing notification that keeps [LinkService] alive. */
    fun linkOngoing(status: String): Notification =
        base(CH_LINK)
            .setContentTitle("BobBot link")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(null, null))
            .addAction(0, "Stop", stopLinkIntent())
            .build()

    /** Re-post the ongoing notification so the service can reflect state changes. */
    fun updateLink(status: String) = post(ID_LINK, linkOngoing(status))

    fun cancelLink() {
        runCatching { manager.cancel(ID_LINK) }
    }

    // ---- internals ----

    private fun base(channel: String) = NotificationCompat.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_stat_bobbot)
        .setColor(0xFF7C9CFF.toInt())
        .setColorized(false)

    private fun openApp(sessionId: String?, profile: String?): PendingIntent? {
        val cls = runCatching { Class.forName("com.bobbot.MainActivity") }.getOrNull() ?: return null
        val intent = Intent(ctx, cls).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (sessionId != null) putExtra("open_session", sessionId)
            if (profile != null) putExtra("open_profile", profile)
        }
        return PendingIntent.getActivity(
            ctx,
            seq.incrementAndGet(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Direct reply from the shade: the text goes to the same session through [ReplyReceiver]. */
    private fun replyAction(notificationId: Int, sessionId: String, profile: String): NotificationCompat.Action {
        val remote = androidx.core.app.RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply").build()
        val intent = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(ReplyReceiver.EXTRA_SESSION, sessionId)
            putExtra(ReplyReceiver.EXTRA_PROFILE, profile)
            putExtra(ReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(ctx, notificationId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, "Reply", pending).addRemoteInput(remote).setAllowGeneratedReplies(false).build()
    }

    /** Swap a message notification for a short confirmation after a reply went out. */
    fun replied(notificationId: Int, bot: String, ok: Boolean, text: String) {
        val n = base(CH_BOTS)
            .setContentTitle(if (ok) "Sent to $bot" else "Reply to $bot failed")
            .setContentText(text.take(120))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 4_000 else 30_000)
            .setContentIntent(openApp(null, bot))
            .build()
        post(notificationId, n)
    }

    private fun stopLinkIntent(): PendingIntent {
        val intent = Intent(ctx, LinkService::class.java).setAction(LinkService.ACTION_STOP)
        return PendingIntent.getService(
            ctx,
            7,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, n: Notification) {
        if (!canPost()) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted; dropping notification")
            return
        }
        try {
            manager.notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify rejected", e)
        }
    }
}
