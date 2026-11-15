/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline lyric translation using ML Kit's dynamically downloaded language
 * models. The model is fetched once for a language pair and then used locally.
 *
 * Model downloads and translation requests are bounded: a stalled Google Play
 * Services task must never leave the lyric surface in its loading state forever.
 * Lyrics remain visible while translation is prepared.
 */
object OnDeviceLyricsTranslator {
    private const val MAX_BATCH_CHARACTERS = 1_800
    private const val MAX_BATCH_LINES = 24
    private const val FALLBACK_PARALLELISM = 4
    private const val LANGUAGE_ID_TIMEOUT_MS = 20_000L
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 120_000L
    private const val TRANSLATION_TIMEOUT_MS = 45_000L

    // The full-page and over-cover lyric surfaces can request the same model
    // around the same time. Avoid starting duplicate model downloads.
    private val modelDownloadMutex = Mutex()

    suspend fun translate(
        lines: List<String>,
        targetLanguageTag: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()

        val sourceSample = lines.asSequence()
            .filter { it.isNotBlank() }
            .take(40)
            .joinToString(separator = " ")
            .take(2_000)
        if (sourceSample.isBlank()) return@withContext lines

        val sourceTag = identifySourceLanguage(sourceSample)
        val sourceCode = sourceTag.trim().replace('_', '-').substringBefore('-')
        val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceCode)
            ?: throw IOException("The detected lyric language is not supported")

        val targetCode = targetLanguageTag.trim()
            .replace('_', '-')
            .substringBefore('-')
            .lowercase()
        val targetLanguage = TranslateLanguage.fromLanguageTag(targetCode)
            ?: throw IOException("The selected translation language is not supported")

        if (sourceLanguage == targetLanguage) return@withContext lines

        val uniqueLines = lines.filter { it.isNotBlank() }.distinct()
        if (uniqueLines.isEmpty()) return@withContext lines

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        )

        try {
            modelDownloadMutex.withLock {
                awaitTask(
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                    MODEL_DOWNLOAD_TIMEOUT_MS,
                    "Preparing the offline translation model"
                )
            }

            val translatedByLine = LinkedHashMap<String, String>()
            for (batch in createBatches(uniqueLines)) {
                currentCoroutineContext().ensureActive()

                // A failed ML Kit request is reported immediately. Only a
                // malformed batch mapping falls back to per-line translation.
                val batchResult = translateBatch(translator, batch)
                if (batchResult != null) {
                    batch.forEachIndexed { index, source ->
                        translatedByLine[source] = batchResult[index]
                    }
                } else {
                    translatedByLine.putAll(translateIndividually(translator, batch))
                }
            }

            if (translatedByLine.size != uniqueLines.size) {
                throw IOException("The offline translator returned incomplete lyrics")
            }

            lines.map { line ->
                if (line.isBlank()) "" else translatedByLine[line]
                    ?: throw IOException("A lyric line was not translated")
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun identifySourceLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        return try {
            awaitTask(
                identifier.identifyLanguage(text),
                LANGUAGE_ID_TIMEOUT_MS,
                "Identifying the lyric language"
            ).takeIf { it.isNotBlank() && it != "und" }
                ?: throw IOException("Could not identify the lyric language")
        } finally {
            identifier.close()
        }
    }

    private suspend fun <T> awaitTask(
        task: Task<T>,
        timeoutMillis: Long,
        operation: String
    ): T {
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    task.addOnCompleteListener { completed ->
                        if (!continuation.isActive) return@addOnCompleteListener
                        if (completed.isSuccessful) {
                            continuation.resume(completed.result)
                        } else {
                            continuation.resumeWithException(
                                completed.exception ?: IOException("$operation failed")
                            )
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Convert only this helper's timeout to a normal failure. The caller
            // can then show its retry message instead of leaving "Preparing…" up.
            throw IOException("$operation timed out", timeout)
        }
    }

    private fun createBatches(lines: List<String>): List<List<String>> {
        if (lines.isEmpty()) return emptyList()

        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentCharacters = 0

        for (line in lines) {
            val requiredCharacters = line.length + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_LINES ||
                    currentCharacters + requiredCharacters > MAX_BATCH_CHARACTERS)
            ) {
                batches += current
                current = mutableListOf()
                currentCharacters = 0
            }

            current += line
            currentCharacters += line.length + if (current.size == 1) 0 else 1
        }

        if (current.isNotEmpty()) batches += current
        return batches
    }

    private suspend fun translateBatch(
        translator: Translator,
        batch: List<String>
    ): List<String>? {
        if (batch.isEmpty()) return emptyList()

        // ML Kit may remove newline characters. Use code-like markers so each
        // translated segment can be mapped back to its original lyric line.
        val markers = batch.indices.map(::lineMarker)
        val markedText = batch.indices.joinToString(separator = " ") { index ->
            "${batch[index]} ${markers[index]}"
        }
        val translated = awaitTask(
            translator.translate(markedText),
            TRANSLATION_TIMEOUT_MS,
            "Translating a lyric batch"
        ).trim()

        val translatedLines = ArrayList<String>(batch.size)
        var cursor = 0
        markers.forEach { marker ->
            val markerPosition = translated.indexOf(marker, cursor)
            if (markerPosition < 0) return null

            val translatedLine = translated.substring(cursor, markerPosition).trim()
            if (translatedLine.isBlank()) return null

            translatedLines += translatedLine
            cursor = markerPosition + marker.length
        }

        val trailingText = translated.substring(cursor).trim()
        if (trailingText.any { it.isLetterOrDigit() }) return null
        return translatedLines
    }

    private fun lineMarker(index: Int): String =
        "ZXQ${index.toString().padStart(4, '0')}QXZ"

    private suspend fun translateIndividually(
        translator: Translator,
        batch: List<String>
    ): Map<String, String> = coroutineScope {
        batch.chunked(FALLBACK_PARALLELISM).flatMap { group ->
            group.map { source ->
                async(Dispatchers.IO) {
                    source to awaitTask(
                        translator.translate(source),
                        TRANSLATION_TIMEOUT_MS,
                        "Translating a lyric line"
                    ).trim()
                }
            }.awaitAll()
        }.toMap()
    }
}
