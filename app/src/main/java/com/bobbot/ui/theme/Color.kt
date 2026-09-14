package com.bobbot.ui.theme

import androidx.compose.ui.graphics.Color

object BobColors {
    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF12151C)
    val SurfaceRaised = Color(0xFF181C25)
    val SurfaceHigh = Color(0xFF1F2430)
    val Outline = Color(0xFF2A3040)
    val OutlineSoft = Color(0xFF1E232E)
    val Text = Color(0xFFE8EAF0)
    val TextMuted = Color(0xFF9AA3B5)
    val TextFaint = Color(0xFF626B7D)
    val Accent = Color(0xFF7C9CFF)
    val AccentDeep = Color(0xFF4F6FE6)
    val AccentSoft = Color(0xFF223055)
    val Mint = Color(0xFF5EEAD4)
    val MintSoft = Color(0xFF16342F)
    val Amber = Color(0xFFF5B761)
    val AmberSoft = Color(0xFF3A2E17)
    val Rose = Color(0xFFFF6B6B)
    val RoseSoft = Color(0xFF3B1D22)
    val Violet = Color(0xFFB794F6)
    val VioletSoft = Color(0xFF2B2140)

    /** Messages-app bubbles: yours in blue, the bot's in grey. */
    val UserBubble = Color(0xFF2F62F0)
    val UserBubbleText = Color(0xFFFFFFFF)
    val BotBubble = Color(0xFF23272F)
}

/** Deterministic accent for a bot name so each bot has a stable identity color. */
fun botColor(name: String): Color {
    val palette = listOf(
        BobColors.Accent, BobColors.Mint, BobColors.Amber, BobColors.Violet,
        Color(0xFFFF8FB1), Color(0xFF6EE7B7), Color(0xFFFCA5A5), Color(0xFF93C5FD),
    )
    if (name.isBlank()) return BobColors.Accent
    var h = 0
    for (c in name) h = (h * 31 + c.code) and 0x7fffffff
    return palette[h % palette.size]
}
