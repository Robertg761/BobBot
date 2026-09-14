package com.bobbot.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.bobbot.R
import com.bobbot.data.repo.BotNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One long-lived conversation shortcut per bot. Android 11+ wants a shortcut behind a
 * MessagingStyle notification before it will file it under Conversations, let the user mark it
 * priority, or float it as a bubble.
 *
 * A plain object taking a Context: the roster lives in [BotNames], which is process-wide, so this
 * needs no injection and cannot become a Hilt cycle. Every entry point is best-effort; a launcher
 * that refuses shortcuts must never cost the user a notification.
 */
object Shortcuts {
    private const val TAG = "Shortcuts"
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"
    private const val PREFIX = "bot:"
    private val ACCENT = 0xFF7C9CFF.toInt()

    /** Profiles seen so far, so a refresh from one caller never drops another's bots. */
    private val known = linkedSetOf<String>()
    private val attached = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val icons = HashMap<String, IconCompat>()

    fun idFor(profile: String): String = PREFIX + profile

    /** Republish the roster. Safe to call often; shortcuts are keyed by id, so this overwrites. */
    fun publish(context: Context, profiles: List<String>) {
        runCatching {
            val wanted = synchronized(known) {
                known.addAll(profiles.filter { it.isNotBlank() })
                known.toList()
            }
            val app = context.applicationContext
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).takeIf { it > 0 } ?: 4
            for (profile in wanted.take(max)) {
                ShortcutManagerCompat.pushDynamicShortcut(app, shortcut(app, profile))
            }
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    /** Make sure one bot has a shortcut, called just before a notification points at it. */
    fun ensure(context: Context, profile: String) {
        if (profile.isBlank()) return
        publish(context, listOf(profile))
    }

    /**
     * Follow the roster: display names arrive asynchronously (persona headings), and every screen
     * that loads bots feeds [BotNames]. Idempotent, so the app and the service can both call it.
     */
    fun attach(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                // collectLatest debounces the burst of updates a roster refresh produces:
                // personas land one bot at a time.
                BotNames.names.collectLatest { names ->
                    delay(400)
                    // A renamed bot needs a redrawn initial.
                    synchronized(icons) { icons.clear() }
                    publish(app, names.keys.toList())
                }
            }.onFailure { Log.w(TAG, "roster watch failed", it) }
        }
    }

    fun person(context: Context, profile: String): Person = Person.Builder()
        .setName(BotNames.display(profile))
        .setKey(profile)
        .setIcon(avatar(context, profile))
        .setBot(true)
        .build()

    private fun shortcut(context: Context, profile: String): ShortcutInfoCompat {
        val name = BotNames.display(profile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, "com.bobbot.MainActivity")
            putExtra("open_profile", profile)
        }
        return ShortcutInfoCompat.Builder(context, idFor(profile))
            .setShortLabel(name)
            .setLongLabel(name)
            .setLongLived(true)
            .setPerson(person(context, profile))
            .setCategories(setOf(CATEGORY_CONVERSATION))
            .setIcon(avatar(context, profile))
            .setIntent(intent)
            .build()
    }

    /** A round letter avatar on the app accent, so a Conversations row is not a grey blob. */
    fun avatar(context: Context, profile: String): IconCompat = synchronized(icons) {
        icons.getOrPut(profile) {
            runCatching { letterIcon(BotNames.display(profile)) }
                .getOrElse { IconCompat.createWithResource(context, R.mipmap.ic_launcher) }
        }
    }

    private fun letterIcon(name: String): IconCompat {
        val size = 192
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        // Adaptive icons are masked by the launcher, so the colour has to reach every edge.
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        val letter = name.trim().firstOrNull()?.uppercase() ?: "B"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.4f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
