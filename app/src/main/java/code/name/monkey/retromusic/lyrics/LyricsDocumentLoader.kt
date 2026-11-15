/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.parser.LrcLyricsParser
import code.name.monkey.retromusic.lyrics.parser.TtmlLyricsParser
import code.name.monkey.retromusic.lyrics.translation.LyricsTranslationCache
import code.name.monkey.retromusic.lyrics.translation.OnDeviceLyricsTranslator
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.LyricUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class LoadedLyrics(
    val document: LyricDocument,
    val translations: List<String>?,
    val translationRequested: Boolean,
    val translationFailed: Boolean
)

/**
 * Loads local lyric files independently from translation so karaoke can start
 * immediately. On-device translation runs separately after local parsing.
 */
object LyricsDocumentLoader {
    suspend fun loadDocument(context: Context, song: Song): LyricDocument? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val selectedSource = LyricsPreferences.source(appContext, song)

            fun loadTtml(): LyricDocument? = runCatching {
                LyricUtil.getTtmlLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?.let(TtmlLyricsParser::parse)
            }.getOrNull()

            fun loadLrc(): LyricDocument? = runCatching {
                val lrcText = LyricUtil.getSyncedLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?: LyricUtil.getEmbeddedSyncedLyrics(song.data)
                lrcText?.let(LrcLyricsParser::parse)
            }.getOrNull()

            when (selectedSource) {
                // A per-track TTML preference must not hide available LRC lyrics
                // when that track has no usable TTML file.
                LyricsSource.TTML -> loadTtml() ?: loadLrc()
                LyricsSource.LRC -> loadLrc()
                LyricsSource.AUTOMATIC -> loadTtml() ?: loadLrc()
            }
        }

    suspend fun translate(
        context: Context,
        song: Song,
        document: LyricDocument
    ): List<String> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val targetLanguage = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("lyrics_translation_language", "id") ?: "id"

        val songKey = LyricsPreferences.cacheSongKey(song)
        val lyricsHash = MessageDigest.getInstance("SHA-256")
            .digest(
                document.lines.joinToString("\u001F") { it.text }
                    .toByteArray(Charsets.UTF_8)
            )
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        // Version the engine key so results from older online engines are never reused.
        val engineCacheKey = "google_mlkit_offline_v2_$lyricsHash"
        val cache = LyricsTranslationCache(appContext)
        val cached = cache.get(songKey, targetLanguage, engineCacheKey)
            ?.takeIf { translations ->
                translations.size == document.lines.size &&
                    document.lines.indices.all { index ->
                        document.lines[index].text.isBlank() || translations[index].isNotBlank()
                    }
            }
        if (cached != null) return@withContext cached

        val translated = OnDeviceLyricsTranslator.translate(
            document.lines.map { it.text },
            targetLanguage
        )
        if (translated.size != document.lines.size) {
            throw IllegalStateException("The translation result does not match the lyric lines")
        }
        cache.put(songKey, targetLanguage, engineCacheKey, translated)
        translated
    }

    /**
     * Compatibility helper for callers that need a fully translated result.
     * UI surfaces should prefer loadDocument() and translate() separately.
     */
    suspend fun load(context: Context, song: Song): LoadedLyrics? {
        val document = loadDocument(context, song) ?: return null
        val requested = LyricsPreferences.isTranslationEnabled(context, song)
        if (!requested) return LoadedLyrics(document, null, false, false)

        val translated = try {
            translate(context, song, document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return LoadedLyrics(document, translated, true, translated == null)
    }
}
