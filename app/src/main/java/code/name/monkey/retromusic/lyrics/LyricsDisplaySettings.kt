/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import androidx.preference.PreferenceManager

enum class LyricsSurface(
    val preferencePrefix: String,
    val defaultTextSize: Int
) {
    REPLACE_COVER("lyrics_replace", 30),
    OVER_COVER("lyrics_over", 25),
    FULL_PAGE("lyrics_page", 28)
}

data class LyricsDisplayStyle(
    val textSizeSp: Float = 28f,
    val fontFamily: String = "sans-serif",
    val alignment: String = "center",
    val lineSpacingDp: Int = 5,
    val translationTextSizeSp: Float = 14f,
    val translationFontFamily: String = "sans-serif",
    val translationColor: String = "theme"
)

object LyricsDisplaySettings {
    fun read(context: Context, surface: LyricsSurface): LyricsDisplayStyle {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val prefix = surface.preferencePrefix
        return LyricsDisplayStyle(
            textSizeSp = preferences.getInt(
                "${prefix}_text_size",
                surface.defaultTextSize
            ).coerceIn(14, 42).toFloat(),
            fontFamily = preferences.getString("${prefix}_font_family", "sans-serif")
                ?.takeIf { it.isNotBlank() } ?: "sans-serif",
            alignment = preferences.getString("${prefix}_alignment", "center")
                ?.takeIf { it in setOf("left", "center", "right") } ?: "center",
            lineSpacingDp = preferences.getInt("lyrics_line_spacing", 5).coerceIn(0, 20),
            translationTextSizeSp = preferences.getInt("lyrics_translation_text_size", 14)
                .coerceIn(10, 30).toFloat(),
            translationFontFamily = preferences.getString(
                "lyrics_translation_font_family", "sans-serif"
            )?.takeIf { it.isNotBlank() } ?: "sans-serif",
            translationColor = preferences.getString("lyrics_translation_color", "theme")
                ?.takeIf { it in setOf("theme", "white", "silver", "cyan", "pink", "amber", "mint") }
                ?: "theme"
        )
    }
}
