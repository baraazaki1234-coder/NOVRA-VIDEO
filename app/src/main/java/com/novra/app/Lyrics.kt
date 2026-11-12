package com.novra.app

data class LyricLine(val timeMs: Long, val text: String)

private val lrcTimestamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

fun parseLrc(source: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    source.lineSequence().forEach { rawLine ->
        val matches = lrcTimestamp.findAll(rawLine).toList()
        if (matches.isEmpty()) return@forEach
        val lyricText = rawLine.substring(matches.last().range.last + 1).trim()
        if (lyricText.isEmpty()) return@forEach
        matches.forEach { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
            val fractionText = match.groupValues[3]
            val millis = when (fractionText.length) {
                1 -> (fractionText.toLongOrNull() ?: 0L) * 100L
                2 -> (fractionText.toLongOrNull() ?: 0L) * 10L
                3 -> fractionText.toLongOrNull() ?: 0L
                else -> 0L
            }
            result += LyricLine((minutes * 60L + seconds) * 1000L + millis, lyricText)
        }
    }
    return result.sortedBy { it.timeMs }
}
