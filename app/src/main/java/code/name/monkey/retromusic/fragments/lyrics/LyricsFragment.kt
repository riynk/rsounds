/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.lyrics.KaraokeLyricsView
import code.name.monkey.retromusic.lyrics.LyricsDisplaySettings
import code.name.monkey.retromusic.lyrics.LyricsSurface
import code.name.monkey.retromusic.lyrics.LyricsPreferences
import code.name.monkey.retromusic.lyrics.LyricsSource
import code.name.monkey.retromusic.lyrics.LyricsDocumentLoader
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.parser.LrcLyricsParser
import code.name.monkey.retromusic.lyrics.parser.TtmlLyricsParser
import code.name.monkey.retromusic.lyrics.translation.LyricsTranslationCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.TagWriter
import code.name.monkey.retromusic.databinding.FragmentLyricsBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.openUrl
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.fragments.NowPlayingScreen
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.LrcView
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.FileUtils
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.UriUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import com.afollestad.materialdialogs.input.input
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.set

class LyricsFragment : AbsMainActivityFragment(R.layout.fragment_lyrics),
    MusicProgressViewUpdateHelper.Callback {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private lateinit var song: Song

    private lateinit var normalLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var editSyncedLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var cacheFile: File
    private var syncedLyrics: String = ""
    private lateinit var syncedFileUri: Uri

    private var lyricsType: LyricsType = LyricsType.NORMAL_LYRICS

    private val googleSearchLrcUrl: String
        get() {
            var baseUrl = "http://www.google.com/search?"
            var query = song.title + "+" + song.artistName
            query = "q=" + query.replace(" ", "+") + " lyrics"
            baseUrl += query
            return baseUrl
        }

    private lateinit var updateHelper: MusicProgressViewUpdateHelper
    private var lyricsLoadJob: Job? = null
    private var lyricsLoadRequest = 0
    private var lyricsBackgroundRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normal lyrics launcher
        normalLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    FileUtils.copyFileToUri(requireContext(), cacheFile, song.uri)
                }
            }
        editSyncedLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    requireContext().contentResolver.openOutputStream(syncedFileUri)?.use { os ->
                        (os as FileOutputStream).channel.truncate(0)
                        os.write(syncedLyrics.toByteArray())
                        os.flush()
                    }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = Fade()
        exitTransition = Fade()
        _binding = FragmentLyricsBinding.bind(view)
        updateHelper = MusicProgressViewUpdateHelper(this, 33, 500)
        updateTitleSong()
        setupLyricsView()

        setupWakelock()
        setupViews()
        setupToolbar()
        updateLyricsBackground()
    }

    private fun setupLyricsView() {
        binding.karaokeLyricsView.apply {
            setStyle(LyricsDisplaySettings.read(requireContext(), LyricsSurface.FULL_PAGE))
            setOnLineClickListener { time -> MusicPlayerRemote.seekTo(time.toInt()) }
            setOnTranslationRetryListener { loadLyrics() }
        }
        binding.lyricsView.apply {
            setCurrentColor(accentColor())
            setTimeTextColor(accentColor())
            setTimelineColor(accentColor())
            setTimelineTextColor(accentColor())
            setDraggable(true, LrcView.OnPlayClickListener {
                MusicPlayerRemote.seekTo(it.toInt())
                return@OnPlayClickListener true
            })
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
        if (binding.karaokeLyricsView.isVisible) {
            binding.karaokeLyricsView.updateTime(progress.toLong())
        }
    }

    private fun setupViews() {
        binding.editButton.accentColor()
        binding.editButton.setOnClickListener {
            when (lyricsType) {
                LyricsType.SYNCED_LYRICS, LyricsType.ADVANCED_LYRICS -> {
                    editSyncedLyrics()
                }
                LyricsType.NORMAL_LYRICS -> {
                    editNormalLyrics()
                }
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateTitleSong()
        updateLyricsBackground()
        refreshLyricsMenu()
        loadLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateTitleSong()
        refreshLyricsMenu()
        loadLyrics()
    }

    private fun updateTitleSong() {
        song = MusicPlayerRemote.currentSong
    }

    private fun setupToolbar() {
        mainActivity.setSupportActionBar(binding.toolbar)
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun updateLyricsBackground() {
        if (_binding == null) return
        val enabled = (PreferenceUtil.nowPlayingScreen == NowPlayingScreen.PlainAtmosphere)
        binding.lyricsBackground.isVisible = enabled
        binding.lyricsBackgroundScrim.isVisible = enabled
        if (!enabled) return

        binding.appBarLayout.setBackgroundColor(Color.TRANSPARENT)
        binding.toolbar.setBackgroundColor(Color.TRANSPARENT)
        binding.toolbar.setTitleTextColor(Color.WHITE)
        ToolbarContentTintHelper.colorizeToolbar(binding.toolbar, Color.WHITE, requireActivity())
        binding.normalLyrics.setTextColor(Color.WHITE)
        binding.karaokeLyricsView.setTextColors(
            Color.WHITE,
            0xB3FFFFFF.toInt(),
            accentColor()
        )

        val requestId = ++lyricsBackgroundRequest
        val currentSong = song
        val source = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("plain_blur_background_source", "album_art")
        val blur = BlurTransformation.Builder(requireContext())
            .blurRadius(PreferenceUtil.blurAmount.toFloat())
            .build()

        fun loadSongArtwork() {
            if (_binding == null || requestId != lyricsBackgroundRequest) return
            Glide.with(this)
                .load(RetroGlideExtension.getSongModel(currentSong))
                .simpleSongCoverOptions(currentSong)
                .transform(blur)
                .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
                .into(binding.lyricsBackground)
        }

        if (source == "artist_image") {
            libraryViewModel.artist(currentSong.artistId).observe(viewLifecycleOwner) { artist ->
                if (_binding == null || requestId != lyricsBackgroundRequest) return@observe
                if (artist.id == -1L) {
                    loadSongArtwork()
                } else {
                    Glide.with(this)
                        .load(RetroGlideExtension.getArtistModel(artist))
                        .artistImageOptions(artist)
                        .transform(blur)
                        .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
                        .into(binding.lyricsBackground)
                }
            }
        } else {
            loadSongArtwork()
        }
    }

    private fun setLyricsSourceSubtitle(sourceFormat: String?, hasWordTiming: Boolean = false) {
        binding.toolbar.subtitle = when {
            sourceFormat == "ttml" && hasWordTiming ->
                getString(R.string.lyrics_status_ttml_words)
            sourceFormat == "ttml" ->
                getString(R.string.lyrics_status_ttml_lines)
            sourceFormat == "lrc" ->
                getString(R.string.lyrics_status_lrc_lines)
            else -> null
        }
    }

    private fun refreshLyricsMenu() {
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_toggle_lyrics_translation)?.isChecked =
            LyricsPreferences.isTranslationEnabled(requireContext(), song)
        menu.findItem(R.id.action_choose_lyrics_source)?.title =
            getString(R.string.action_choose_lyrics_source) + " — " +
                getString(LyricsPreferences.source(requireContext(), song).titleRes)
    }

    private fun setupWakelock() {
        if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("screen_on_lyrics", false)
        ) requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_lyrics, menu)
        refreshLyricsMenu()
        if ((PreferenceUtil.nowPlayingScreen == NowPlayingScreen.PlainAtmosphere)) {
            ToolbarContentTintHelper.colorizeToolbar(binding.toolbar, Color.WHITE, requireActivity())
        } else {
            ToolbarContentTintHelper.handleOnCreateOptionsMenu(
                requireContext(),
                binding.toolbar,
                menu,
                ATHToolbarActivity.getToolbarBackgroundColor(binding.toolbar)
            )
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            openUrl(googleSearchLrcUrl)
            true
        }
        R.id.action_toggle_lyrics_translation -> {
            val enabled = !LyricsPreferences.isTranslationEnabled(requireContext(), song)
            LyricsPreferences.setTranslationEnabled(requireContext(), song, enabled)
            item.isChecked = enabled
            loadLyrics()
            true
        }
        R.id.action_choose_lyrics_source -> {
            LyricsPreferences.showSourcePicker(requireContext(), song) {
                loadLyrics()
                mainActivity.invalidateOptionsMenu()
            }
            true
        }
        else -> false
    }

    @SuppressLint("CheckResult")
    private fun editNormalLyrics(lyrics: String? = null) {
        val file = File(song.data)
        val content = lyrics ?: try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        val song = song

        materialDialog().show {
            title(res = R.string.edit_normal_lyrics)
            input(
                hintRes = R.string.paste_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    if (VersionUtils.hasR()) {
                        cacheFile = TagWriter.writeTagsToFilesR(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )[0]
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                        normalLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        TagWriter.writeTagsToFiles(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadNormalLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }


    @SuppressLint("CheckResult")
    private fun editSyncedLyrics(lyrics: String? = null) {
        val content = lyrics ?: LyricUtil.getStringFromLrc(LyricUtil.getSyncedLyricsFile(song))

        val song = song
        materialDialog().show {
            title(res = R.string.edit_synced_lyrics)
            input(
                hintRes = R.string.paste_timeframe_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                if (VersionUtils.hasR()) {
                    syncedLyrics = input.toString()
                    val lrcFile = LyricUtil.getSyncedLyricsFile(song)
                    if (lrcFile?.exists() == true) {
                        syncedFileUri =
                            UriUtil.getUriFromPath(requireContext(), lrcFile.absolutePath)
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(syncedFileUri)
                            )
                        editSyncedLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                        fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                        viewLifecycleOwner.lifecycleScope.launch {
                            cacheFile = TagWriter.writeTagsToFilesR(
                                requireContext(),
                                AudioTagInfo(listOf(song.data), fieldKeyValueMap, null)
                            )[0]
                            val pendingIntent = MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                            normalLyricsLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent).build()
                            )
                        }
                    }
                } else {
                    LyricUtil.writeLrc(song, input.toString())
                }
            }
            positiveButton(res = R.string.save) {
                loadLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun loadNormalLyrics() {
        val file = File(song.data)
        val lyrics = try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        binding.normalLyrics.isVisible = !lyrics.isNullOrEmpty()
        binding.noLyricsFound.isVisible = lyrics.isNullOrEmpty()
        val style = LyricsDisplaySettings.read(requireContext(), LyricsSurface.FULL_PAGE)
        binding.normalLyrics.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
            typeface = Typeface.create(style.fontFamily, Typeface.NORMAL)
            gravity = when (style.alignment) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER
            }
            text = lyrics
        }
    }

    /**
     * @return success
     */
    private fun loadLRCLyrics(): Boolean {
        val lrcFile = LyricUtil.getSyncedLyricsFile(song)
        if (lrcFile != null) {
            binding.lyricsView.loadLrc(lrcFile)
        } else {
            val embeddedLyrics = LyricUtil.getEmbeddedSyncedLyrics(song.data)
            if (embeddedLyrics != null) {
                binding.lyricsView.loadLrc(embeddedLyrics)
            } else {
                binding.lyricsView.setLabel(getString(R.string.empty))
                return false
            }
        }
        return true
    }

    private fun loadLyrics() {
        if (_binding == null) return
        val requestId = ++lyricsLoadRequest
        lyricsLoadJob?.cancel()
        val currentSong = song

        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val translationRequested =
                LyricsPreferences.isTranslationEnabled(context, currentSong)

            // Remove the previous song before loading the new local lyric document.
            binding.karaokeLyricsView.clearDocument()
            binding.karaokeLyricsView.setTranslationStatus(
                if (translationRequested) getString(R.string.lyrics_translation_loading) else null
            )
            binding.karaokeLyricsView.isVisible = true
            binding.lyricsView.isVisible = false
            binding.normalLyrics.isVisible = false
            binding.noLyricsFound.isVisible = false
            binding.editButton.isVisible = false

            val document = try {
                LyricsDocumentLoader.loadDocument(context, currentSong)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isCurrentLyricsRequest(requestId, currentSong)) return@launch

            if (document != null) {
                binding.karaokeLyricsView.setStyle(
                    LyricsDisplaySettings.read(context, LyricsSurface.FULL_PAGE)
                )
                if (PreferenceUtil.nowPlayingScreen == NowPlayingScreen.PlainAtmosphere) {
                    // Keep the existing LRC theme palette. TTML applies its own
                    // dim/white two-colour palette in KaraokeLyricsView.
                    binding.karaokeLyricsView.setTextColors(
                        Color.WHITE,
                        0xB3FFFFFF.toInt(),
                        accentColor()
                    )
                }

                val highlightWords = preferences.getBoolean("lyrics_word_highlight", true)
                // Keep original timed lyrics visible while the on-device model prepares and translates them.
                binding.karaokeLyricsView.setDocument(document, null, highlightWords)
                setLyricsSourceSubtitle(
                    document.sourceFormat,
                    document.hasWordTiming
                )
                binding.karaokeLyricsView.isVisible = true
                binding.lyricsView.isVisible = false
                binding.normalLyrics.isVisible = false
                binding.noLyricsFound.isVisible = false
                binding.editButton.isVisible = document.sourceFormat != "ttml"
                lyricsType = if (document.sourceFormat == "ttml") {
                    LyricsType.ADVANCED_LYRICS
                } else LyricsType.SYNCED_LYRICS

                if (translationRequested) {
                    try {
                        val translations = LyricsDocumentLoader.translate(
                            context,
                            currentSong,
                            document
                        )
                        if (!isCurrentLyricsRequest(requestId, currentSong)) return@launch
                        binding.karaokeLyricsView.setDocument(
                            document,
                            translations,
                            highlightWords
                        )
                        binding.karaokeLyricsView.setTranslationStatus(
                            getString(R.string.lyrics_translation_attribution)
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrentLyricsRequest(requestId, currentSong)) {
                            binding.karaokeLyricsView.setTranslationStatus(
                                getString(R.string.lyrics_translation_failed)
                            )
                        }
                    }
                } else {
                    binding.karaokeLyricsView.setTranslationStatus(null)
                }
                return@launch
            }

            binding.karaokeLyricsView.setTranslationStatus(null)
            binding.karaokeLyricsView.isVisible = false
            binding.editButton.isVisible = true
            val hasSyncedLrc = loadLRCLyrics()
            setLyricsSourceSubtitle(if (hasSyncedLrc) "lrc" else null)
            lyricsType = if (!hasSyncedLrc) {
                binding.lyricsView.isVisible = false
                loadNormalLyrics()
                LyricsType.NORMAL_LYRICS
            } else {
                binding.normalLyrics.isVisible = false
                binding.noLyricsFound.isVisible = false
                binding.lyricsView.isVisible = true
                LyricsType.SYNCED_LYRICS
            }
        }
    }

    private fun isCurrentLyricsRequest(requestId: Int, requestedSong: Song): Boolean =
        _binding != null &&
            requestId == lyricsLoadRequest &&
            requestedSong.id == song.id &&
            requestedSong.id == MusicPlayerRemote.currentSong.id

    override fun onResume() {
        super.onResume()
        updateTitleSong()
        updateLyricsBackground()
        refreshLyricsMenu()
        updateHelper.start()
        // Refresh lyric display settings when returning from Settings.
        loadLyrics()
    }

    override fun onPause() {
        super.onPause()
        updateHelper.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lyricsBackgroundRequest++
        lyricsLoadRequest++
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        if (MusicPlayerRemote.playingQueue.isNotEmpty()) mainActivity.expandPanel()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }

    enum class LyricsType {
        NORMAL_LYRICS,
        SYNCED_LYRICS,
        ADVANCED_LYRICS
    }
}
