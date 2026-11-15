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
package code.name.monkey.retromusic.fragments.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import code.name.monkey.retromusic.LYRICS_TYPE
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter.AlbumCoverFragment
import code.name.monkey.retromusic.databinding.FragmentPlayerAlbumCoverBinding
import code.name.monkey.retromusic.extensions.isColorLight
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.fragments.NowPlayingScreen
import code.name.monkey.retromusic.fragments.NowPlayingScreen.*
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.fragments.base.goToLyrics
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.KaraokeLyricsView
import code.name.monkey.retromusic.lyrics.LyricsDisplaySettings
import code.name.monkey.retromusic.lyrics.LyricsDocumentLoader
import code.name.monkey.retromusic.lyrics.LyricsPreferences
import code.name.monkey.retromusic.lyrics.LyricsSurface
import code.name.monkey.retromusic.transform.CarousalPagerTransformer
import code.name.monkey.retromusic.transform.ParallaxPagerTransformer
import code.name.monkey.retromusic.util.CoverLyricsType
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor

class PlayerAlbumCoverFragment : AbsMusicServiceFragment(R.layout.fragment_player_album_cover),
    ViewPager.OnPageChangeListener, MusicProgressViewUpdateHelper.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlayerAlbumCoverBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var currentPosition: Int = 0
    val viewPager get() = binding.viewPager

    /**
     * Returns the artwork view belonging to the currently centred pager page.
     * Over-cover lyrics use these exact bounds instead of the taller player slot.
     */
    fun currentArtworkView(): View? {
        val pager = _binding?.viewPager ?: return null
        val candidates = ArrayList<View>()

        fun collect(view: View) {
            if (view.id == R.id.player_image) candidates += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }

        collect(pager)
        val pagerLocation = IntArray(2)
        pager.getLocationOnScreen(pagerLocation)
        val pagerCenter = pagerLocation[0] + pager.width / 2f

        return candidates
            .filter { it.isShown && it.width > 0 && it.height > 0 }
            .minByOrNull { artwork ->
                val location = IntArray(2)
                artwork.getLocationOnScreen(location)
                kotlin.math.abs(location[0] + artwork.width / 2f - pagerCenter)
            }
    }

    private val colorReceiver = object : AlbumCoverFragment.ColorReceiver {
        override fun onColorReady(color: MediaNotificationProcessor, request: Int) {
            if (currentPosition == request) {
                notifyColorChange(color)
            }
        }
    }
    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var lyricsLoadJob: Job? = null
    private var lyricsLoadRequest = 0

    private val lrcView: KaraokeLyricsView get() = binding.lyricsView

    fun removeSlideEffect() {
        val transformer = ParallaxPagerTransformer(R.id.player_image)
        transformer.setSpeed(0.3f)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewPager.setPageTransformer(false, transformer)
            }
        }
    }

    private fun updateLyrics() {
        val currentSong = MusicPlayerRemote.currentSong
        val requestId = ++lyricsLoadRequest
        lyricsLoadJob?.cancel()

        // Clear the previous track immediately so its timestamps cannot follow the next song.
        lrcView.clearDocument()
        val context = requireContext()
        val translationRequested =
            LyricsPreferences.isTranslationEnabled(context, currentSong)
        lrcView.setTranslationStatus(
            if (translationRequested) getString(R.string.lyrics_translation_loading) else null
        )

        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val document = try {
                LyricsDocumentLoader.loadDocument(context, currentSong)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            if (!isCurrentLyricsRequest(requestId, currentSong.id)) return@launch
            lrcView.setStyle(
                LyricsDisplaySettings.read(context, LyricsSurface.REPLACE_COVER)
            )

            if (document == null) {
                lrcView.setTranslationStatus(null)
                lrcView.setEmptyMessage(getString(R.string.no_lyrics_found))
                return@launch
            }

            val showWordHighlight = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("lyrics_word_highlight", true)
            lrcView.setDocument(document, null, showWordHighlight)

            if (!translationRequested) {
                lrcView.setTranslationStatus(null)
                return@launch
            }

            try {
                val translations = LyricsDocumentLoader.translate(
                    context,
                    currentSong,
                    document
                )
                if (!isCurrentLyricsRequest(requestId, currentSong.id)) return@launch
                lrcView.setDocument(document, translations, showWordHighlight)
                lrcView.setTranslationStatus(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentLyricsRequest(requestId, currentSong.id)) {
                    lrcView.setTranslationStatus(
                        getString(R.string.lyrics_translation_failed)
                    )
                }
            }
        }
    }

    private fun isCurrentLyricsRequest(requestId: Int, songId: Long): Boolean =
        _binding != null &&
            requestId == lyricsLoadRequest &&
            songId == MusicPlayerRemote.currentSong.id

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        if (_binding == null) return
        lrcView.updateTime(progress.toLong())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerAlbumCoverBinding.bind(view)
        setupViewPager()
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 33, 500)
        maybeInitLyrics()
        lrcView.apply {
            setStyle(
                LyricsDisplaySettings.read(requireContext(), LyricsSurface.REPLACE_COVER)
            )
            setOnLineClickListener { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
            }
            setOnClickListener {
                goToLyrics(requireActivity())
            }
            setOnTranslationRetryListener { updateLyrics() }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.addOnPageChangeListener(this)
        val nps = PreferenceUtil.nowPlayingScreen

        if (nps == Full || nps == Classic || nps == Fit || nps == Gradient) {
            binding.viewPager.offscreenPageLimit = 2
        } else if (PreferenceUtil.isCarouselEffect) {
            val metrics = resources.displayMetrics
            val ratio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
            binding.viewPager.clipToPadding = false
            val padding =
                if (ratio >= 1.777f) {
                    40
                } else {
                    100
                }
            binding.viewPager.setPadding(padding, 0, padding, 0)
            binding.viewPager.pageMargin = 0
            binding.viewPager.setPageTransformer(false, CarousalPagerTransformer(requireContext()))
        } else {
            binding.viewPager.offscreenPageLimit = 2
            binding.viewPager.setPageTransformer(
                true,
                PreferenceUtil.albumCoverTransform
            )
        }
    }

    override fun onResume() {
        super.onResume()
        maybeInitLyrics()
        if (PreferenceUtil.showLyrics) updateLyrics()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        binding.viewPager.removeOnPageChangeListener(this)
        lyricsLoadRequest++
        lyricsLoadJob?.cancel()
        progressViewUpdateHelper?.stop()
        _binding = null
    }

    override fun onServiceConnected() {
        updatePlayingQueue()
        if (PreferenceUtil.showLyrics) updateLyrics()
    }

    override fun onPlayingMetaChanged() {
        if (viewPager.currentItem != MusicPlayerRemote.position) {
            viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        }
        if (PreferenceUtil.showLyrics) updateLyrics()
    }

    override fun onQueueChanged() {
        updatePlayingQueue()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SHOW_LYRICS -> {
                if (PreferenceUtil.showLyrics) {
                    maybeInitLyrics()
                } else {
                    showLyrics(false)
                    progressViewUpdateHelper?.stop()
                }
            }
            LYRICS_TYPE -> maybeInitLyrics()
            else -> if (key?.startsWith("lyrics_") == true && PreferenceUtil.showLyrics) {
                updateLyrics()
            }
        }
    }

    private fun setLRCViewColors(@ColorInt primaryColor: Int, @ColorInt secondaryColor: Int) {
        lrcView.setTextColors(primaryColor, secondaryColor)
    }

    private fun showLyrics(visible: Boolean) {
        val replaceCover = PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
        // The fading-edge layer belongs only to Replace Cover. Keep it out of
        // Over Cover so it cannot add a second translucent rectangle over artwork.
        binding.fadingEdgeLayout.isVisible = visible && replaceCover
        binding.coverLyrics.isVisible = false
        binding.lyricsView.isVisible = false
        binding.viewPager.isVisible = true
        val lyrics: View = if (replaceCover) {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, if (visible) 0F else 1F).start()
            lrcView
        } else {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, 1F).start()
            binding.coverLyrics
        }
        ObjectAnimator.ofFloat(lyrics, View.ALPHA, if (visible) 1F else 0F).apply {
            doOnEnd {
                lyrics.isVisible = visible
            }
            start()
        }
    }

    private fun maybeInitLyrics() {
        if (PreferenceUtil.showLyrics) {
            showLyrics(true)
            if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
                progressViewUpdateHelper?.start()
            } else {
                progressViewUpdateHelper?.stop()
            }
        } else {
            showLyrics(false)
            progressViewUpdateHelper?.stop()
        }
    }

    private fun updatePlayingQueue() {
        val adapter = binding.viewPager.adapter
        if (adapter is AlbumCoverPagerAdapter) {
            adapter.updateData(MusicPlayerRemote.playingQueue)
        } else {
            binding.viewPager.adapter = AlbumCoverPagerAdapter(
                parentFragmentManager,
                MusicPlayerRemote.playingQueue
            )
        }
        binding.viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        onPageSelected(MusicPlayerRemote.position)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        currentPosition = position
        (childFragmentManager.findFragmentById(R.id.cover_lyrics) as? CoverLyricsFragment)
            ?.refreshArtworkEffects()
        if (binding.viewPager.adapter != null) {
            (binding.viewPager.adapter as AlbumCoverPagerAdapter).receiveColor(
                colorReceiver,
                position
            )
        }
        if (position != MusicPlayerRemote.position) {
            MusicPlayerRemote.playSongAt(position)
        }
    }

    override fun onPageScrollStateChanged(state: Int) = Unit

    private fun notifyColorChange(color: MediaNotificationProcessor) {
        callbacks?.onColorChanged(color)
        val primaryColor = MaterialValueHelper.getPrimaryTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )
        val secondaryColor = MaterialValueHelper.getSecondaryDisabledTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )

        when (PreferenceUtil.nowPlayingScreen) {
            Flat, Normal, Material -> if (PreferenceUtil.isAdaptiveColor) {
                setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            } else {
                setLRCViewColors(primaryColor, secondaryColor)
            }
            Color, Classic -> setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            Blur, PlainAtmosphere -> setLRCViewColors(
                android.graphics.Color.WHITE,
                ColorUtil.withAlpha(android.graphics.Color.WHITE, 0.5f)
            )
            else -> setLRCViewColors(primaryColor, secondaryColor)
        }
    }

    fun setCallbacks(listener: Callbacks) {
        callbacks = listener
    }

    interface Callbacks {
        fun onColorChanged(color: MediaNotificationProcessor)
        fun onFavoriteToggled()
    }

    companion object {
        val TAG: String = PlayerAlbumCoverFragment::class.java.simpleName
    }
}
