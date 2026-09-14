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
        /** Renamed from "link" so the importance could drop to MIN; Android fixes a channel's importance at creation. */
        const val CH_LINK = "link_quiet"
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
        val link = NotificationChannelCompat.Builder(CH_LINK, NotificationManager.IMPORTANCE_MIN)
            .setName("Background link")
            .setDescription("Keeps the connection to your Hermes server open. Android requires this while BobBot listens in the background; it makes no sound and shows no icon.")
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        val relay = NotificationChannelCompat.Builder(CH_RELAY, NotificationManager.IMPORTANCE_LOW)
            .setName("Bot-to-bot hand-offs")
            .setDescription("A bot finishing or stalling on work another bot gave it")
            .build()
        manager.createNotificationChannelsCompat(listOf(bots, link, relay))
        // One-time migration from the old, louder "link" channel.
        val flags = ctx.getSharedPreferences("notifier", Context.MODE_PRIVATE)
        if (!flags.getBoolean("link_channel_migrated", false)) {
            runCatching { manager.deleteNotificationChannel("link") }
            flags.edit().putBoolean("link_channel_migrated", true).apply()
        }
    }

    /** Recent messages per conversation so a bot's notification reads as one thread, not a pile. */
    private val threads = HashMap<String, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    private fun threadId(profile: String?, sessionId: String?): Int =
        ("thread:" + (profile ?: "") + ":" + (sessionId ?: "")).hashCode() and 0x7fffffff

    // ---- public API ----

    /**
     * A message from a bot, shown as a conversation: one notification per chat that grows as the bot
     * keeps talking. Tapping it opens that chat; a task chat's title is shown under the bot's name.
     */
    fun botMessage(bot: String, title: String, text: String, sessionId: String?, profile: String?) {
        val body = text.ifBlank { "(no content)" }
        val id = threadId(profile ?: bot, sessionId)
        val sender = androidx.core.app.Person.Builder().setName(bot.ifBlank { "Bot" }).setKey(profile ?: bot).build()
        val style = NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("You").build())
        val messages = synchronized(threads) {
            threads.getOrPut(id.toString()) { ArrayDeque() }.also { q ->
                q.addLast(NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), sender))
                while (q.size > 6) q.removeFirst()
            }.toList()
        }
        messages.forEach { style.addMessage(it) }
        if (title.isNotBlank() && title != bot) style.setConversationTitle(title)
        val n = base(CH_BOTS)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openApp(sessionId, profile))
            .apply { if (sessionId != null && profile != null) addAction(replyAction(id, sessionId, profile)) }
            .build()
        post(id, n)
    }

    /** One bot talking to another on the board. */
    fun botToBot(fromProfile: String, toProfile: String, text: String) {
        val from = com.bobbot.data.repo.BotNames.display(fromProfile)
        val to = com.bobbot.data.repo.BotNames.display(toProfile)
        val body = text.ifBlank { "(no content)" }
        val n = base(CH_RELAY)
            .setContentTitle("$from → $to")
            .setContentText(body.lineSequence().firstOrNull()?.take(120) ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(null, toProfile))
            .build()
        post(seq.incrementAndGet(), n)
    }

    /** Your main bot escalated a specialist's permission request; only you can decide it. */
    fun decisionNeeded(profile: String, tool: String, reason: String, authority: String) {
        val who = com.bobbot.data.repo.BotNames.display(profile)
        val boss = com.bobbot.data.repo.BotNames.display(authority)
        val body = "$who wants to use $tool." + (if (reason.isNotBlank()) " $boss: $reason" else "")
        val n = base(CH_BOTS)
            .setContentTitle("$boss needs your decision")
            .setContentText(body.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(null, profile))
            .build()
        post(seq.incrementAndGet(), n)
    }

    /** The result of a scheduled automation run. */
    fun automation(name: String, bot: String, output: String, ok: Boolean) {
        val body = output.ifBlank { if (ok) "finished" else "failed" }
        val who = com.bobbot.data.repo.BotNames.display(bot)
        val n = base(CH_BOTS)
            .setContentTitle(if (ok) "$who · $name" else "$who · $name failed")
            .setContentText(body.lineSequence().firstOrNull()?.take(120) ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
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
            .setContentTitle("BobBot is listening for your bots")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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

    // ---- internals ----

    private fun base(channel: String) = NotificationCompat.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_stat_bobbot)
        .setColor(0xFF7C9CFF.toInt())
        .setColorized(false)

    private fun openApp(sessionId: String?, profile: String?, team: Boolean = false): PendingIntent? {
        val cls = runCatching { Class.forName("com.bobbot.MainActivity") }.getOrNull() ?: return null
        val intent = Intent(ctx, cls).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (sessionId != null) putExtra("open_session", sessionId)
            if (profile != null) putExtra("open_profile", profile)
            if (team) putExtra("open_team", true)
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
    fun replied(notificationId: Int, profile: String, ok: Boolean, text: String) {
        val bot = com.bobbot.data.repo.BotNames.display(profile)
        synchronized(threads) { threads.remove(notificationId.toString()) }
        val n = base(CH_BOTS)
            .setContentTitle(if (ok) "Sent to $bot" else "Reply to $bot failed")
            .setContentText(text.take(120))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 4_000 else 30_000)
            .setContentIntent(openApp(null, profile))
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
