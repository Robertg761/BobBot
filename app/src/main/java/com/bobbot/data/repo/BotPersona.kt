package com.bobbot.data.repo

/** Give every new bot its chosen identity while preserving the persona instructions. */
internal fun newBotPersona(name: String, description: String, soul: String?): String {
    val displayName = BotNames.fallback(name)
    var body = soul?.trim().orEmpty()
    val title = body.lineSequence().firstOrNull().orEmpty()
    if (title.startsWith("# ") &&
        (BotNames.isGenericHeading(title.removePrefix("# ").trim()) ||
            title.removePrefix("# ").trim().equals(displayName, ignoreCase = true))) {
        body = body.substringAfter('\n', "").trim()
    }
    if (body.isBlank()) body = "You are a specialist assistant. ${description.trim()}\n" +
        "You have your own identity and report to the team's authority bot."
    return "# $displayName\n\nYou are $displayName.\n\n$body"
}
