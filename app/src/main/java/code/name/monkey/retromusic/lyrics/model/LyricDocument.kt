/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.model

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val isBackground: Boolean = false
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long?,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val agent: String? = null
)

data class LyricDocument(
    val lines: List<LyricLine>,
    val sourceFormat: String
) {
    val hasWordTiming: Boolean
        get() = lines.any { it.words.isNotEmpty() }
}
