/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.model.Song
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest

enum class LyricsSource(val value: String, val titleRes: Int) {
    AUTOMATIC("auto", R.string.lyrics_source_auto),
    LRC("lrc", R.string.lyrics_source_lrc),
    TTML("ttml", R.string.lyrics_source_ttml);

    companion object {
        fun fromValue(value: String?): LyricsSource =
            values().firstOrNull { it.value == value } ?: AUTOMATIC
    }
}

/**
 * Preferences that belong to one track, rather than the whole player.
 * The stable key is derived from the file path so toggles survive process restarts
 * and cannot accidentally follow the next song in the queue.
 */
object LyricsPreferences {
    private const val TRANSLATION_PREFIX = "lyrics_translation_enabled_"
    private const val SOURCE_PREFIX = "lyrics_source_"

    private fun preferences(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun isTranslationEnabled(context: Context, song: Song): Boolean =
        preferences(context).getBoolean(TRANSLATION_PREFIX + songKey(song), false)

    fun setTranslationEnabled(context: Context, song: Song, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean(TRANSLATION_PREFIX + songKey(song), enabled)
            .apply()
    }

    fun source(context: Context, song: Song): LyricsSource =
        LyricsSource.fromValue(
            preferences(context).getString(SOURCE_PREFIX + songKey(song), null)
        )

    fun setSource(context: Context, song: Song, source: LyricsSource) {
        preferences(context).edit()
            .putString(SOURCE_PREFIX + songKey(song), source.value)
            .apply()
    }

    fun cacheSongKey(song: Song): String = songKey(song)

    fun showSourcePicker(
        context: Context,
        song: Song,
        onSelected: (LyricsSource) -> Unit
    ) {
        val sources = LyricsSource.values()
        val labels = sources.map { context.getString(it.titleRes) }.toTypedArray()
        val selectedIndex = sources.indexOf(source(context, song)).coerceAtLeast(0)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_choose_lyrics_source)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selected = sources[which]
                setSource(context, song, selected)
                dialog.dismiss()
                onSelected(selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun songKey(song: Song): String {
        val identity = song.data.takeIf { it.isNotBlank() } ?: "media:${song.id}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
