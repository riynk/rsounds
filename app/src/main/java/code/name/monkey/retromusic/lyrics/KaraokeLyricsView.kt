/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import code.name.monkey.retromusic.lyrics.engine.LyricsSynchronizer
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine
import code.name.monkey.retromusic.lyrics.model.LyricWord
import kotlin.math.roundToInt

private const val TTML_INACTIVE_TEXT_COLOR = 0x40FFFFFF

/**
 * Shared karaoke renderer for Replace Cover, Over Cover and the full lyrics page.
 * Timed TTML/LRC segments are progressively filled during their actual time
 * interval rather than changing colour only after the segment has finished.
 */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(24), dp(8), dp(32))
    }
    private val lineViews = mutableListOf<TextView>()
    private val translationViews = mutableListOf<TextView?>()
    private var document: LyricDocument? = null
    private var translations: List<String>? = null
    private var highlightWords = true
    private var currentLine = -2
    private var currentPositionMs = 0L
    private var lineClickListener: ((Long) -> Unit)? = null
    private var translationRetryListener: (() -> Unit)? = null
    private var translationStatus: String? = null
    private var translationStatusView: TextView? = null
    private var style = LyricsDisplayStyle()
    private var compactMode = false
    private var compactLineCount = 3

    private var primaryTextColor = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private var secondaryTextColor =
        resolveThemeColor(android.R.attr.textColorSecondary, 0xFFBDBDBD.toInt())
    private var accentTextColor = resolveThemeColor(android.R.attr.colorAccent, 0xFF80CBC4.toInt())

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyAlignment()
    }

    fun setOnLineClickListener(listener: (Long) -> Unit) {
        lineClickListener = listener
    }

    fun setOnTranslationRetryListener(listener: (() -> Unit)?) {
        translationRetryListener = listener
        translationStatusView?.isClickable = listener != null
    }

    fun setStyle(value: LyricsDisplayStyle) {
        style = value
        applyAlignment()
        document?.let { renderLines(it, currentLine) }
        translationStatusView?.typeface = resolveTypeface(style.translationFontFamily)
        translationStatusView?.setTextColor(translationTextColor())
    }

    /**
     * Shows only the active lyric and its immediate neighbours. Used by the
     * artwork overlay so the lyric surface never turns into a full-page list.
     */
    fun setCompactLineCount(count: Int) {
        val normalized = count.coerceIn(1, 4)
        if (compactLineCount == normalized) return
        compactLineCount = normalized
        document?.let { renderLines(it, currentLine) }
    }

    fun setCompactMode(enabled: Boolean) {
        if (compactMode == enabled) return
        compactMode = enabled
        content.setPadding(
            dp(8),
            if (enabled) dp(8) else dp(24),
            dp(8),
            if (enabled) dp(8) else dp(32)
        )
        content.layoutParams = content.layoutParams?.apply {
            height = if (enabled) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        }
        applyAlignment()
        document?.let { renderLines(it, currentLine) }
    }

    fun setTextColors(
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int? = null
    ) {
        primaryTextColor = primaryColor
        secondaryTextColor = secondaryColor
        if (accentColor != null) accentTextColor = accentColor
        document?.let { renderLines(it, currentLine) }
        translationStatusView?.setTextColor(translationTextColor())
    }

    fun setTranslationStatus(message: String?) {
        translationStatus = message
        refreshTranslationStatus()
    }

    fun clearDocument() {
        document = null
        translations = null
        lineViews.clear()
        translationViews.clear()
        content.removeAllViews()
        translationStatusView = null
        translationStatus = null
        currentLine = -2
        currentPositionMs = 0L
        scrollTo(0, 0)
    }

    fun setEmptyMessage(message: String) {
        clearDocument()
        val label = TextView(context).apply {
            text = message
            gravity = alignmentGravity()
            typeface = resolveTypeface(style.fontFamily)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (style.textSizeSp * 0.72f).coerceAtLeast(14f)
            )
            setTextColor(secondaryTextColor)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        content.addView(
            label,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun setDocument(
        value: LyricDocument,
        translation: List<String>? = null,
        enableWordHighlight: Boolean = true
    ) {
        document = value
        translations = translation?.takeIf { it.size == value.lines.size }
        highlightWords = enableWordHighlight
        currentLine = -2
        lineViews.clear()
        translationViews.clear()
        translationStatusView = null
        content.removeAllViews()

        value.lines.forEachIndexed { index, line ->
            val lyric = TextView(context).apply {
                setPadding(dp(12), dp(12), dp(12), dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener { lineClickListener?.invoke(line.startMs) }
            }
            lineViews += lyric
            val translated = translations?.getOrNull(index)?.takeIf { it.isNotBlank() }
            val subtitle = translated?.let { text ->
                TextView(context).apply {
                    this.text = text
                    setPadding(dp(12), 0, dp(12), dp(16))
                    alpha = 0.9f
                }
            }
            translationViews += subtitle
        }

        refreshTranslationStatus()
        applyAlignment()
        post { updateTime(currentPositionMs) }
    }

    fun updateTime(positionMs: Long, offsetMs: Long = 0L) {
        val value = document ?: return
        currentPositionMs = (positionMs + offsetMs).coerceAtLeast(0L)
        val position = LyricsSynchronizer.locate(value, currentPositionMs)
        val lineChanged = position.lineIndex != currentLine
        currentLine = position.lineIndex

        if (lineChanged) {
            renderLines(value, position.lineIndex)
            if (!compactMode && position.lineIndex >= 0) {
                post {
                    val row = lineViews.getOrNull(position.lineIndex) ?: return@post
                    smoothScrollTo(
                        0,
                        (row.top - (height - row.height) / 2).coerceAtLeast(0)
                    )
                }
            }
        } else if (position.lineIndex >= 0) {
            // The active spans read currentPositionMs when drawn. Only invalidate:
            // rebuilding Spannable text on every tick forces extra layout work.
            renderActiveLine()
        }
    }

    private fun renderLines(value: LyricDocument, activeIndex: Int) {
        content.removeAllViews()
        translationStatusView?.let { status ->
            content.addView(
                status,
                LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )
        }

        if (compactMode) {
            val visibleCount = minOf(compactLineCount, value.lines.size)
            if (visibleCount == 0) return
            val focusIndex = activeIndex.coerceIn(0, value.lines.lastIndex)
            val maxStart = (value.lines.size - visibleCount).coerceAtLeast(0)
            val startIndex = (focusIndex - visibleCount / 2).coerceIn(0, maxStart)
            for (index in startIndex until startIndex + visibleCount) {
                addRenderedLine(index, activeIndex, compact = true)
            }
        } else {
            value.lines.indices.forEach { index ->
                addRenderedLine(index, activeIndex, compact = false)
            }
        }
    }

    private fun addRenderedLine(index: Int, activeIndex: Int, compact: Boolean) {
        val line = document?.lines?.getOrNull(index) ?: return
        val active = index == activeIndex
        val lyric = lineViews[index]
        val targetAlpha = when {
            active -> 1f
            kotlin.math.abs(index - activeIndex) == 1 -> 0.72f
            else -> 0.42f
        }
        val hasProgressiveHighlight =
            active && highlightWords && line.words.isNotEmpty()
        val isTtml = document?.sourceFormat.equals("ttml", ignoreCase = true)
        val inactiveColor = inactiveTextColor(isTtml)

        lyric.animate().cancel()
        lyric.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.textSizeSp * when {
                active -> 1f
                compact -> 0.76f
                else -> 0.78f
            }
        )
        lyric.setPadding(
            dp(12),
            if (compact) dp(6) else dp(12),
            dp(12),
            if (compact) dp(6) else dp(2)
        )
        lyric.typeface = resolveTypeface(style.fontFamily)
        lyric.gravity = alignmentGravity()
        // TTML is intentionally limited to two colours on every surface:
        // inactive text stays dim, while the active timing sweep fills in white.
        // LRC keeps its established theme-aware primary/accent treatment.
        lyric.setTextColor(
            when {
                hasProgressiveHighlight && isTtml -> inactiveColor
                hasProgressiveHighlight -> activeKaraokeBaseColor()
                active && isTtml -> Color.WHITE
                active -> primaryTextColor
                else -> inactiveColor
            }
        )
        lyric.text = if (active) highlightedText(line, isTtml) else line.text

        val subtitle = translationViews.getOrNull(index)
        subtitle?.animate()?.cancel()
        if (subtitle != null) {
            subtitle.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                style.translationTextSizeSp * if (compact) 0.9f else 1f
            )
            subtitle.setPadding(
                dp(12),
                0,
                dp(12),
                if (compact) dp(5) else dp(16)
            )
            subtitle.typeface = resolveTypeface(style.translationFontFamily)
            subtitle.gravity = alignmentGravity()
            subtitle.setTextColor(translationTextColor())
        }

        if (compact) {
            lyric.alpha = 0f
            lyric.translationY = if (index < activeIndex) -dp(8).toFloat() else dp(8).toFloat()
        } else {
            lyric.alpha = targetAlpha
            lyric.translationY = 0f
        }
        val lyricLayoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = if (subtitle != null && (!compact || active)) {
                0
            } else {
                dp(style.lineSpacingDp)
            }
        }
        content.addView(lyric, lyricLayoutParams)

        // Keep the artwork readable: only the active line gets a translation
        // subtitle in the compact overlay. Full-page lyrics retain all subtitles.
        if (subtitle != null && (!compact || active)) {
            if (compact) {
                subtitle.alpha = 0f
                subtitle.translationY = dp(5).toFloat()
            } else {
                subtitle.alpha = if (active) 0.9f else 0.42f
                subtitle.translationY = 0f
            }
            val subtitleLayoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(style.lineSpacingDp)
            }
            content.addView(subtitle, subtitleLayoutParams)
        }

        if (compact) {
            lyric.animate()
                .alpha(targetAlpha)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            if (subtitle != null && active) {
                subtitle.animate()
                    .alpha(0.9f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } else {
            lyric.animate().alpha(targetAlpha).setDuration(260L).start()
            subtitle?.animate()?.alpha(if (active) 0.9f else 0.42f)
                ?.setDuration(260L)?.start()
        }
    }

    private fun translationTextColor(): Int = when (style.translationColor) {
        "white" -> Color.WHITE
        "silver" -> 0xFFE0E0E0.toInt()
        "cyan" -> 0xFF80DEEA.toInt()
        "pink" -> 0xFFFF80AB.toInt()
        "amber" -> 0xFFFFD180.toInt()
        "mint" -> 0xFFA5D6A7.toInt()
        else -> secondaryTextColor
    }

    private fun inactiveTextColor(isTtml: Boolean): Int =
        if (isTtml) TTML_INACTIVE_TEXT_COLOR else secondaryTextColor

    private fun activeKaraokeBaseColor(): Int =
        blendColors(primaryTextColor, secondaryTextColor, 0.45f)

    private fun blendColors(start: Int, end: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        val inverse = 1f - amount
        return Color.argb(
            (Color.alpha(start) * inverse + Color.alpha(end) * amount).roundToInt(),
            (Color.red(start) * inverse + Color.red(end) * amount).roundToInt(),
            (Color.green(start) * inverse + Color.green(end) * amount).roundToInt(),
            (Color.blue(start) * inverse + Color.blue(end) * amount).roundToInt()
        )
    }

    private fun renderActiveLine() {
        val index = currentLine
        if (index in lineViews.indices) {
            lineViews[index].invalidate()
        }
    }

    private fun highlightedText(line: LyricLine, isTtml: Boolean): CharSequence {
        if (!highlightWords || line.words.isEmpty()) return line.text
        val spannable = SpannableString(line.text)
        line.words.forEach { word ->
            val start = word.startIndex.coerceIn(0, spannable.length)
            val end = word.endIndex.coerceIn(start, spannable.length)
            if (end > start) {
                // TTML uses a dim base and a white progressive fill. Enhanced
                // LRC retains its original blended base and theme accent.
                spannable.setSpan(
                    ProgressiveLyricsSpan(
                        word = word,
                        positionProvider = { currentPositionMs },
                        baseColor = if (isTtml) inactiveTextColor(true) else activeKaraokeBaseColor(),
                        highlightColor = if (isTtml) Color.WHITE else accentTextColor
                    ),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    private fun refreshTranslationStatus() {
        translationStatusView?.let(content::removeView)
        translationStatusView = null

        val message = translationStatus ?: return
        val status = TextView(context).apply {
            text = message
            gravity = alignmentGravity()
            typeface = resolveTypeface(style.translationFontFamily)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(translationTextColor())
            alpha = 0.9f
            setPadding(dp(12), dp(8), dp(12), dp(12))
            isClickable = translationRetryListener != null
            isFocusable = translationRetryListener != null
            setOnClickListener { translationRetryListener?.invoke() }
        }
        translationStatusView = status
        content.addView(
            status,
            0,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun applyAlignment() {
        content.gravity = if (compactMode) Gravity.CENTER else alignmentGravity()
        lineViews.forEach { it.gravity = alignmentGravity() }
        translationViews.filterNotNull().forEach { it.gravity = alignmentGravity() }
        translationStatusView?.gravity = alignmentGravity()
    }

    private fun alignmentGravity(): Int = when (style.alignment) {
        "left" -> Gravity.LEFT or Gravity.CENTER_VERTICAL
        "right" -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    private fun resolveTypeface(family: String): Typeface = when (family) {
        "default" -> Typeface.DEFAULT
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getColorStateList(0)?.defaultColor ?: values.getColor(0, fallback)
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class ProgressiveLyricsSpan(
        private val word: LyricWord,
        private val positionProvider: () -> Long,
        private val baseColor: Int,
        private val highlightColor: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fontMetricsInt: Paint.FontMetricsInt?
        ): Int = paint.measureText(text, start, end).roundToInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val value = text.subSequence(start, end).toString()
            val basePaint = TextPaint(paint).apply { color = baseColor }
            val width = basePaint.measureText(value)
            canvas.drawText(value, x, y.toFloat(), basePaint)

            val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
            val progress = ((positionProvider() - word.startMs).toFloat() / duration)
                .coerceIn(0f, 1f)
            val fillWidth = width * progress
            if (fillWidth > 0f) {
                val save = canvas.save()
                canvas.clipRect(x, top.toFloat(), x + fillWidth, bottom.toFloat())
                basePaint.color = highlightColor
                canvas.drawText(value, x, y.toFloat(), basePaint)
                canvas.restoreToCount(save)
            }
        }
    }
}
