/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.parser

import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine
import code.name.monkey.retromusic.lyrics.model.LyricWord

/**
 * Parses standard LRC and Enhanced LRC. Word timing is only exposed when the
 * input actually contains <mm:ss.xx> markers; standard LRC is never guessed.
 */
object LrcLyricsParser {
    private val linePattern = Regex("""^((?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])+)(.*)$""")
    private val lineTimePattern = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val wordTimePattern = Regex("""<(\d{1,3}:\d{2}(?:[.:]\d{1,3})?)>""")
    private val offsetPattern = Regex("""^\[offset:([+-]?\d+)]$""", RegexOption.IGNORE_CASE)

    fun parse(source: String): LyricDocument? {
        if (source.isBlank()) return null

        val parsed = mutableListOf<LyricLine>()
        var offsetMs = 0L

        source.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            offsetPattern.matchEntire(line)?.let {
                offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            val match = linePattern.matchEntire(line) ?: return@forEach
            val body = match.groupValues[2]
            val timestamps = lineTimePattern.findAll(match.groupValues[1])
                .mapNotNull { parseTime(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                .toList()
            if (timestamps.isEmpty()) return@forEach

            val wordMarkers = wordTimePattern.findAll(body).toList()
            val plainText = wordTimePattern.replace(body, "")
            val words = wordMarkers.mapIndexedNotNull { index, marker ->
                val start = parseClock(marker.groupValues[1]) ?: return@mapIndexedNotNull null
                val rawStart = marker.range.last + 1
                val rawEnd = wordMarkers.getOrNull(index + 1)?.range?.first ?: body.length
                val token = body.substring(rawStart, rawEnd)
                val leading = token.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) token.length else it }
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@mapIndexedNotNull null
                val startIndex = body.substring(0, rawStart).replace(wordTimePattern, "").length + leading
                val endIndex = startIndex + trimmed.length
                LyricWord(
                    startMs = start + offsetMs,
                    endMs = (wordMarkers.getOrNull(index + 1)?.let { parseClock(it.groupValues[1]) }
                        ?: (start + 650L)) + offsetMs,
                    text = trimmed,
                    startIndex = startIndex.coerceIn(0, plainText.length),
                    endIndex = endIndex.coerceIn(0, plainText.length)
                )
            }

            timestamps.forEach { timestamp ->
                val lineStart = (timestamp + offsetMs).coerceAtLeast(0L)
                parsed += LyricLine(
                    startMs = lineStart,
                    endMs = null,
                    text = plainText,
                    words = words.map {
                        it.copy(
                            startMs = (it.startMs).coerceAtLeast(0L),
                            endMs = it.endMs.coerceAtLeast(0L)
                        )
                    }
                )
            }
        }

        if (parsed.isEmpty()) return null
        val sorted = parsed.sortedBy { it.startMs }
        return LyricDocument(
            lines = sorted.mapIndexed { index, line ->
                val nextStart = sorted.getOrNull(index + 1)?.startMs
                line.copy(endMs = nextStart ?: line.endMs ?: line.startMs + 5_000L)
            },
            sourceFormat = if (sorted.any { it.words.isNotEmpty() }) "enhanced-lrc" else "lrc"
        )
    }

    private fun parseClock(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        return parseTime(parts[0], parts[1].substringBefore('.').substringBefore(':'),
            parts[1].substringAfter('.', "").substringBefore(':'))
    }

    private fun parseTime(minutes: String, seconds: String, fraction: String): Long? {
        val min = minutes.toLongOrNull() ?: return null
        val sec = seconds.toLongOrNull() ?: return null
        if (sec !in 0..59) return null
        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return min * 60_000L + sec * 1_000L + millis
    }
}
