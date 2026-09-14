package com.bobbot.update

/** Semantic Versioning 2.0.0 precedence. Build metadata is accepted and ignored for ordering. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        for (index in 0 until maxOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease.getOrNull(index) ?: return -1
            val right = other.preRelease.getOrNull(index) ?: return 1
            val leftIsNumeric = left.all(Char::isDigit)
            val rightIsNumeric = right.all(Char::isDigit)
            val comparison = when {
                leftIsNumeric && rightIsNumeric -> left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
                leftIsNumeric -> -1
                rightIsNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private val pattern = Regex(
            "^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
        )

        fun parseOrNull(raw: String): SemVer? {
            // Debug builds carry a "-debug" suffix that is not valid SemVer pre-release ordering we care about.
            val cleaned = raw.trim().removeSuffix("-debug")
            val match = pattern.matchEntire(cleaned) ?: return null
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty(),
            )
        }
    }
}
