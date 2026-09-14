package com.bobbot.ui.automations

/**
 * Turns the schedule form into the single string Hermes' cron API expects:
 * an interval ("every 30m"), a five-field cron expression, or an ISO timestamp for a one-shot.
 */
enum class ScheduleMode(val label: String) {
    Interval("Every…"),
    Daily("Daily"),
    Weekly("Weekly"),
    Cron("Cron"),
    Once("Once"),
}

val WeekdayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

data class ScheduleDraft(
    val mode: ScheduleMode = ScheduleMode.Interval,
    val minutes: String = "30",
    val time: String = "09:00",
    val weekday: Int = 1,
    val cron: String = "0 9 * * *",
    val once: String = "",
) {
    private val hourMinute: Pair<Int, Int>
        get() {
            val parts = time.trim().split(":", ".", " ").filter { it.isNotBlank() }
            val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
            val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            return h to m
        }

    fun build(): String {
        val (h, m) = hourMinute
        return when (mode) {
            ScheduleMode.Interval -> "every ${minutes.trim().toIntOrNull()?.coerceAtLeast(1) ?: 30}m"
            ScheduleMode.Daily -> "$m $h * * *"
            ScheduleMode.Weekly -> "$m $h * * ${weekday.coerceIn(0, 6)}"
            ScheduleMode.Cron -> cron.trim()
            ScheduleMode.Once -> once.trim()
        }
    }

    val isValid: Boolean
        get() = when (mode) {
            ScheduleMode.Interval -> (minutes.trim().toIntOrNull() ?: 0) > 0
            ScheduleMode.Daily, ScheduleMode.Weekly -> time.contains(":")
            ScheduleMode.Cron -> cron.trim().split(" ").filter { it.isNotBlank() }.size >= 5
            ScheduleMode.Once -> once.trim().length >= 10
        }

    /** A human sentence for the form, so the produced string is never a surprise. */
    fun preview(): String = when (mode) {
        ScheduleMode.Interval -> "Runs every ${minutes.trim().ifBlank { "?" }} minutes"
        ScheduleMode.Daily -> "Runs every day at ${time.trim()}"
        ScheduleMode.Weekly -> "Runs every ${WeekdayNames.getOrElse(weekday) { "day" }} at ${time.trim()}"
        ScheduleMode.Cron -> "Cron: ${cron.trim()}"
        ScheduleMode.Once -> "Runs once at ${once.trim().ifBlank { "(set a time)" }}"
    }
}

/** Best-effort reverse of [ScheduleDraft.build] so editing an existing job starts from the right tab. */
fun parseSchedule(raw: String?): ScheduleDraft {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return ScheduleDraft()

    Regex("""^every\s+(\d+)\s*(m|min|mins|minutes)?\b""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
        return ScheduleDraft(mode = ScheduleMode.Interval, minutes = m.groupValues[1])
    }

    val fields = s.split(" ").filter { it.isNotBlank() }
    if (fields.size == 5) {
        val minute = fields[0].toIntOrNull()
        val hour = fields[1].toIntOrNull()
        val dow = fields[4]
        if (minute != null && hour != null && fields[2] == "*" && fields[3] == "*") {
            val time = "%02d:%02d".format(hour, minute)
            val day = dow.toIntOrNull()
            return if (dow == "*") {
                ScheduleDraft(mode = ScheduleMode.Daily, time = time, cron = s)
            } else if (day != null) {
                ScheduleDraft(mode = ScheduleMode.Weekly, time = time, weekday = day.coerceIn(0, 6), cron = s)
            } else {
                ScheduleDraft(mode = ScheduleMode.Cron, cron = s)
            }
        }
        return ScheduleDraft(mode = ScheduleMode.Cron, cron = s)
    }

    if (s.contains("-") && (s.contains("T") || s.contains(":"))) {
        return ScheduleDraft(mode = ScheduleMode.Once, once = s)
    }
    return ScheduleDraft(mode = ScheduleMode.Cron, cron = s)
}
