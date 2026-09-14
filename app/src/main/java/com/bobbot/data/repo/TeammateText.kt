package com.bobbot.data.repo

/**
 * Recognises the two shapes Hermes uses when another bot speaks inside a Bot Chat.
 *
 * A DM arrives as user text: `Message from 🤖 steve (@steve): <body>`.
 * The reply to a DM this bot sent arrives as a background-process completion:
 * `[IMPORTANT: Background process … Command: hermes -p steve chat … Output:\n<reply>]`.
 */
object TeammateText {
    data class Parsed(val profile: String, val text: String, val reply: Boolean)

    private val dm = Regex("""^Message from (?:🤖 )?([A-Za-z0-9][A-Za-z0-9_-]{0,63}) \(@[A-Za-z0-9_-]+\):\s*""", RegexOption.DOT_MATCHES_ALL)
    private val completion = Regex("""^\[IMPORTANT: Background process .*?Command: (.*?)\nOutput:\n(.*)\]\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val profileFlag = Regex("""(?:^|\s)hermes(?:\.exe)?\s+-p\s+([A-Za-z0-9][A-Za-z0-9_-]{0,63})\s""")
    private val botChat = Regex("""\bchat\b.*\bBot Chat\b""")

    /** The mention middleware aliases the default profile as @hermes. */
    fun profileOf(handle: String): String = if (handle.equals("hermes", ignoreCase = true)) "default" else handle

    fun parse(text: String): Parsed? {
        dm.find(text)?.let { m ->
            val body = text.substring(m.range.last + 1).trim()
            return Parsed(profileOf(m.groupValues[1]), body, reply = false)
        }
        if (!text.startsWith("[IMPORTANT: Background process")) return null
        completion.find(text)?.let { m ->
            val command = m.groupValues[1]
            if (!botChat.containsMatchIn(command)) return null
            val profile = profileFlag.find(" $command ")?.groupValues?.get(1) ?: return null
            val body = m.groupValues[2].trim().ifBlank { return null }
            return Parsed(profileOf(profile), body, reply = true)
        }
        return null
    }

    /** A short line for other background completions: "Background process finished: <command>". */
    fun processTitle(text: String): String {
        val cmd = completion.find(text)?.groupValues?.get(1)?.trim()?.take(80)
        return if (cmd.isNullOrBlank()) "Background process finished" else "Background process finished: $cmd"
    }
}
