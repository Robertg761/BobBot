package com.bobbot.ui.chat

import com.bobbot.data.repo.ChatItem
import kotlin.math.roundToInt

/**
 * A bot turn emits a tool line, a "Thought process" toggle and a status line for every step it
 * takes, which buries the one reply the reader came for. Consecutive steps therefore collapse into
 * a single [Entry.Run] row that opens on tap; everything a person said or was told stays a
 * [Entry.Single].
 */
sealed interface Entry {
    val items: List<ChatItem>

    /** LazyColumn key: the first item of a run never changes as the run grows, so the row is stable. */
    val key: String get() = items.first().id

    data class Single(val item: ChatItem) : Entry {
        override val items: List<ChatItem> = listOf(item)
    }

    data class Run(override val items: List<ChatItem>) : Entry
}

/** Notices the reader must not have to open a row to find. */
private val LoudSystemKinds = setOf("error", "warn")

/** A lone step reads better as itself than as "Worked for 2s · 1 step". */
private const val MIN_RUN = 2

/** True for the quiet machinery of a turn: tool calls, reasoning-only bubbles and status notes. */
fun isWorkingStep(item: ChatItem): Boolean = when (item) {
    is ChatItem.Tool -> true
    // Reasoning arrives on an Assistant bubble that often never gets words of its own; errors and
    // interruptions are that bubble's only visible trace, so those stay out of the run.
    is ChatItem.Assistant -> item.text.isBlank() && item.error == null && item.status != "interrupted"
    is ChatItem.System -> item.kind !in LoudSystemKinds
    is ChatItem.User, is ChatItem.Teammate, is ChatItem.Delegation -> false
}

/** Folds each run of consecutive working steps into one entry, in transcript order. */
fun groupChatItems(items: List<ChatItem>): List<Entry> {
    val out = ArrayList<Entry>(items.size)
    var run = mutableListOf<ChatItem>()
    fun flush() {
        if (run.isEmpty()) return
        if (run.size >= MIN_RUN) out += Entry.Run(run.toList()) else run.forEach { out += Entry.Single(it) }
        run = mutableListOf()
    }
    for (item in items) {
        if (isWorkingStep(item)) run += item else { flush(); out += Entry.Single(item) }
    }
    flush()
    return out
}

/**
 * How long a run took: the span of its timestamps when it carries more than one, else the tool
 * durations the server reported, else unknown (tool and system items carry no time of their own).
 */
fun runDurationS(items: List<ChatItem>): Double? {
    val times = items.mapNotNull { it.at }
    if (times.size >= 2) {
        val span = (times.max() - times.min()) / 1000.0
        if (span >= 1.0) return span
    }
    val tools = items.filterIsInstance<ChatItem.Tool>().mapNotNull { it.durationS }
    val sum = tools.sum()
    return if (tools.isNotEmpty() && sum >= 0.1) sum else null
}

/** "Worked for 40s · 5 steps", or "Working… · 3 steps" while the turn is still running. */
fun workedLabel(steps: Int, durationS: Double?, live: Boolean): String {
    val stepText = if (steps == 1) "1 step" else "$steps steps"
    val head = when {
        live -> "Working…"
        durationS != null -> "Worked for ${shortDuration(durationS)}"
        else -> "Worked"
    }
    return "$head · $stepText"
}

fun shortDuration(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(1)
    return when {
        total < 60 -> "${total}s"
        total < 3600 -> if (total % 60 == 0) "${total / 60}m" else "${total / 60}m ${total % 60}s"
        else -> "${total / 3600}h ${(total % 3600) / 60}m"
    }
}

/** One line of progress for the step a live run is on right now. */
fun stepLabel(item: ChatItem): String = when (item) {
    is ChatItem.Tool -> listOf(item.name, item.context).filter { it.isNotBlank() }.joinToString(" · ")
    is ChatItem.Assistant -> "Thinking…"
    is ChatItem.System -> item.text.removePrefix("[System: ").removeSuffix("]")
    else -> ""
}
