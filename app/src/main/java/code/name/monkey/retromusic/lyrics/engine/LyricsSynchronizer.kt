/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.engine

import code.name.monkey.retromusic.lyrics.model.LyricDocument

data class LyricsPosition(
    val lineIndex: Int,
    val wordIndex: Int
)

object LyricsSynchronizer {
    fun locate(document: LyricDocument, playbackMs: Long, offsetMs: Long = 0L): LyricsPosition {
        val lines = document.lines
        if (lines.isEmpty()) return LyricsPosition(-1, -1)

        val time = (playbackMs + offsetMs).coerceAtLeast(0L)
        var low = 0
        var high = lines.lastIndex
        var lineIndex = -1

        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= time) {
                lineIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        if (lineIndex < 0) return LyricsPosition(-1, -1)
        val words = lines[lineIndex].words
        if (words.isEmpty()) return LyricsPosition(lineIndex, -1)

        low = 0
        high = words.lastIndex
        var wordIndex = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (words[middle].startMs <= time) {
                wordIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        if (wordIndex >= 0 && time >= words[wordIndex].endMs) {
            val next = words.getOrNull(wordIndex + 1)
            if (next == null || time < next.startMs) {
                return LyricsPosition(lineIndex, wordIndex)
            }
        }
        return LyricsPosition(lineIndex, wordIndex)
    }
}
