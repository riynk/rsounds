/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.parser

import android.util.Xml
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine
import code.name.monkey.retromusic.lyrics.model.LyricWord
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.ArrayDeque
import kotlin.math.roundToLong

/**
 * Parses Apple-style TTML without flattening its timed spans into ordinary LRC.
 * A timed span is retained as a timed text segment; no word timestamps are
 * invented when the source only provides line-level or phrase-level timing.
 */
object TtmlLyricsParser {
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
    private val WORD_TOKEN_PATTERN = Regex("""[^\s\u00A0\-\u2010\u2013\u2014]+""")

    private data class SpanFrame(
        val startIndex: Int,
        val beginMs: Long?,
        val endMs: Long?,
        val isBackground: Boolean,
        var hasTimedChild: Boolean = false
    )

    private data class MutableLine(
        val beginMs: Long?,
        val endMs: Long?,
        val agent: String?,
        val text: StringBuilder = StringBuilder(),
        val words: MutableList<LyricWord> = mutableListOf()
    )

    fun parse(xml: String): LyricDocument? {
        if (xml.isBlank()) return null

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))

        val result = mutableListOf<LyricLine>()
        val spans = ArrayDeque<SpanFrame>()
        var line: MutableLine? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> {
                        line = MutableLine(
                            beginMs = parseTime(attribute(parser, "begin")),
                            endMs = parseTime(attribute(parser, "end")),
                            agent = parser.getAttributeValue(TTM_NAMESPACE, "agent")
                        )
                        spans.clear()
                    }

                    "span" -> {
                        val current = line
                        if (current != null) {
                            if (spans.isNotEmpty()) spans.peekLast().hasTimedChild = true

                            val parent = spans.peekLast()
                            val begin = parseTime(attribute(parser, "begin")) ?: parent?.beginMs
                            val end = parseTime(attribute(parser, "end"))
                                ?: parseDuration(attribute(parser, "dur"))?.let { duration ->
                                    begin?.plus(duration)
                                }
                                ?: parent?.endMs
                            val role = parser.getAttributeValue(TTM_NAMESPACE, "role")
                                ?: attribute(parser, "role")
                            val background = role == "x-bg" ||
                                spans.any { it.isBackground }

                            spans.addLast(
                                SpanFrame(
                                    startIndex = current.text.length,
                                    beginMs = begin,
                                    endMs = end,
                                    isBackground = background
                                )
                            )
                        }
                    }

                    "br" -> line?.text?.append('\n')
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    line?.text?.append(parser.text.orEmpty())
                }

                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "span" -> {
                        if (spans.isNotEmpty()) {
                            val frame = spans.removeLast()
                            val current = line
                            val endIndex = current?.text?.length ?: 0
                            if (current != null &&
                                !frame.hasTimedChild &&
                                frame.beginMs != null &&
                                frame.endMs != null &&
                                frame.endMs > frame.beginMs
                            ) {
                                val startIndex = frame.startIndex.coerceIn(0, endIndex)
                                val segmentText = current.text.substring(startIndex, endIndex)
                                if (segmentText.isNotBlank()) {
                                    appendTimedWords(
                                        target = current.words,
                                        segmentText = segmentText,
                                        absoluteStartIndex = startIndex,
                                        beginMs = frame.beginMs,
                                        endMs = frame.endMs,
                                        isBackground = frame.isBackground
                                    )
                                }
                            }
                        }
                    }

                    "p" -> {
                        val current = line
                        if (current != null) {
                            val rawText = current.text.toString()
                            val text = rawText.trim()
                            if (text.isNotEmpty()) {
                                val leading = rawText.indexOfFirst { !it.isWhitespace() }
                                    .coerceAtLeast(0)
                                val words = current.words.mapNotNull { word ->
                                    val start = (word.startIndex - leading).coerceAtLeast(0)
                                    val end = (word.endIndex - leading).coerceAtMost(text.length)
                                    if (end <= start) {
                                        null
                                    } else {
                                        word.copy(
                                            startIndex = start,
                                            endIndex = end,
                                            text = text.substring(start, end)
                                        )
                                    }
                                }.sortedBy { it.startMs }

                                val start = current.beginMs
                                    ?: words.minOfOrNull { it.startMs }
                                    ?: 0L
                                result += LyricLine(
                                    startMs = start.coerceAtLeast(0L),
                                    endMs = current.endMs,
                                    text = text,
                                    words = words,
                                    agent = current.agent
                                )
                            }
                        }
                        line = null
                        spans.clear()
                    }
                }
            }
            parser.next()
        }

        if (result.isEmpty()) return null
        val sorted = result.sortedBy { it.startMs }
        return LyricDocument(
            lines = sorted.mapIndexed { index, lyricLine ->
                lyricLine.copy(
                    endMs = lyricLine.endMs
                        ?: sorted.getOrNull(index + 1)?.startMs
                        ?: lyricLine.startMs + 5_000L
                )
            },
            sourceFormat = "ttml"
        )
    }

    /**
     * Some Apple-style files put a whole phrase in one timed <span>.
     * Split it into word-sized visual units and distribute the span's existing
     * interval evenly. These intermediate times are estimates, not timestamps
     * explicitly supplied by the TTML source.
     */
    private fun appendTimedWords(
        target: MutableList<LyricWord>,
        segmentText: String,
        absoluteStartIndex: Int,
        beginMs: Long,
        endMs: Long,
        isBackground: Boolean
    ) {
        val tokens = WORD_TOKEN_PATTERN.findAll(segmentText).toList()
        if (tokens.isEmpty()) return

        val durationMs = (endMs - beginMs).coerceAtLeast(1L)
        tokens.forEachIndexed { index, token ->
            val tokenStart = beginMs + durationMs * index / tokens.size
            val tokenEnd = if (index == tokens.lastIndex) {
                endMs
            } else {
                beginMs + durationMs * (index + 1) / tokens.size
            }
            if (tokenEnd <= tokenStart) return@forEachIndexed

            val startIndex = absoluteStartIndex + token.range.first
            val endIndex = absoluteStartIndex + token.range.last + 1
            target += LyricWord(
                startMs = tokenStart,
                endMs = tokenEnd,
                text = token.value,
                startIndex = startIndex,
                endIndex = endIndex,
                isBackground = isBackground
            )
        }
    }

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).substringAfter(':') == name) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    /**
     * Supports TTML clock values HH:MM:SS.mmm, MM:SS.mmm and Apple Music's
     * seconds-only form (for example 20.333), plus offset values such as 300ms
     * and 0.3s.
     */
    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val input = value.trim()

        val offset = Regex("""^([+-]?\d+(?:\.\d+)?)(ms|s)$""")
            .matchEntire(input)
        if (offset != null) {
            val amount = offset.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = if (offset.groupValues[2] == "s") 1_000.0 else 1.0
            return (amount * multiplier).roundToLong().coerceAtLeast(0L)
        }

        val parts = input.split(':')
        return when (parts.size) {
            1 -> {
                val seconds = parts[0].toDoubleOrNull() ?: return null
                if (seconds < 0) null else (seconds * 1_000.0).roundToLong()
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val seconds = parts[1].toDoubleOrNull() ?: return null
                if (minutes < 0 || seconds < 0 || seconds >= 60) {
                    null
                } else {
                    minutes * 60_000L + (seconds * 1_000.0).roundToLong()
                }
            }
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val seconds = parts[2].toDoubleOrNull() ?: return null
                if (hours < 0 || minutes !in 0..59 || seconds < 0 || seconds >= 60) {
                    null
                } else {
                    hours * 3_600_000L + minutes * 60_000L +
                        (seconds * 1_000.0).roundToLong()
                }
            }
            else -> null
        }
    }

    private fun parseDuration(value: String?): Long? = parseTime(value)
}
