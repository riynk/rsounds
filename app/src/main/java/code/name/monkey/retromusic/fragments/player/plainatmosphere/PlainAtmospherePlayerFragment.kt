/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.fragments.player.plainatmosphere

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentPlainAtmospherePlayerBinding
import code.name.monkey.retromusic.extensions.playbackBounceAnimation
import code.name.monkey.retromusic.extensions.whichFragment
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.base.goToAlbum
import code.name.monkey.retromusic.fragments.base.goToArtist
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.fragments.player.plain.PlainPlaybackControlsFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder

private const val PLAIN_BLUR_BACKGROUND_SOURCE = "plain_blur_background_source"

class PlainAtmospherePlayerFragment : AbsPlayerFragment(R.layout.fragment_plain_atmosphere_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlainAtmospherePlayerBinding? = null
    private val binding get() = _binding!!
    private lateinit var controlsFragment: PlainPlaybackControlsFragment
    private var lastColor: Int = Color.WHITE
    private var lastRequest: RequestBuilder<Drawable>? = null
    private var backgroundRequest = 0

    override val preserveStatusBarSpacerInFullscreen: Boolean = true

    override val paletteColor: Int
        get() = lastColor

    override fun playerToolbar(): Toolbar = binding.playerToolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlainAtmospherePlayerBinding.bind(view)
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
        val coverFragment: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        coverFragment.setCallbacks(this)

        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_plain_blur_overflow)
            setOnMenuItemClickListener(this@PlainAtmospherePlayerFragment)
            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, requireActivity())
        }
        binding.quickLyrics.setOnClickListener { performQuickAction(R.id.action_toggle_lyrics) }
        binding.quickFavorite.setOnClickListener { performQuickAction(R.id.action_toggle_favorite) }
        binding.quickSleepTimer.setOnClickListener { performQuickAction(R.id.action_sleep_timer) }
        binding.quickQueue.setOnClickListener { performQuickAction(R.id.now_playing) }
        updateQuickActions()

        binding.title.isSelected = true
        binding.text.isSelected = true
        binding.title.setOnClickListener { goToAlbum(requireActivity()) }
        binding.text.setOnClickListener { goToArtist(requireActivity()) }

        updateSong()
        updateBlurBackground()
    }

    private fun performQuickAction(actionId: Int) {
        val button = when (actionId) {
            R.id.action_sleep_timer -> binding.quickSleepTimer
            R.id.action_toggle_lyrics -> binding.quickLyrics
            R.id.action_toggle_favorite -> binding.quickFavorite
            R.id.now_playing -> binding.quickQueue
            else -> null
        }
        button?.playbackBounceAnimation()

        val item = binding.playerToolbar.menu.findItem(actionId) ?: return
        onMenuItemClick(item)
        updateQuickActions()
    }

    private fun updateQuickActions() {
        if (_binding == null) return
        val lyricsEnabled = PreferenceUtil.showLyrics
        val favoriteEnabled =
            binding.playerToolbar.menu.findItem(R.id.action_toggle_favorite)?.isChecked == true
        binding.quickLyrics.isSelected = lyricsEnabled
        binding.quickLyrics.isActivated = lyricsEnabled
        binding.quickFavorite.isSelected = favoriteEnabled
        binding.quickFavorite.isActivated = favoriteEnabled
        onFavoriteIconUpdated(favoriteEnabled, animate = false)
    }

    private fun updateSong() {
        if (_binding == null) return
        val song = MusicPlayerRemote.currentSong
        binding.title.text = song.title
        binding.text.text = song.artistName + " • " + song.albumName
    }

    private fun updateBlurBackground() {
        if (_binding == null) return
        val song = MusicPlayerRemote.currentSong
        val requestId = ++backgroundRequest
        val source = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString(PLAIN_BLUR_BACKGROUND_SOURCE, "album_art")
        val blur = BlurTransformation.Builder(requireContext())
            .blurRadius(PreferenceUtil.blurAmount.toFloat())
            .build()

        fun display(builder: RequestBuilder<Drawable>) {
            if (_binding == null || requestId != backgroundRequest ||
                song.id != MusicPlayerRemote.currentSong.id
            ) return

            val request = builder
                .thumbnail(lastRequest)
                .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
            lastRequest = request.clone()
            request.crossfadeListener().into(binding.colorBackground)
        }

        fun loadSongArtwork() {
            display(
                Glide.with(this)
                    .load(RetroGlideExtension.getSongModel(song))
                    .simpleSongCoverOptions(song)
                    .transform(blur)
            )
        }

        if (source == "artist_image") {
            libraryViewModel.artist(song.artistId).observe(viewLifecycleOwner) { artist: Artist ->
                if (_binding == null || requestId != backgroundRequest ||
                    song.id != MusicPlayerRemote.currentSong.id
                ) return@observe

                if (artist.id == -1L) {
                    loadSongArtwork()
                } else {
                    display(
                        Glide.with(this)
                            .load(RetroGlideExtension.getArtistModel(artist))
                            .artistImageOptions(artist)
                            .transform(blur)
                    )
                }
            }
        } else {
            loadSongArtwork()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateSong()
        updateBlurBackground()
        updateQuickActions()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateSong()
        updateBlurBackground()
        updateQuickActions()
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        updateSong()
        updateBlurBackground()
        updateQuickActions()
    }

    override fun onPause() {
        super.onPause()
        lastRequest = null
    }

    override fun onDestroyView() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        backgroundRequest++
        lastRequest = null
        super.onDestroyView()
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT || key == PLAIN_BLUR_BACKGROUND_SOURCE) {
            updateBlurBackground()
        }
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        controlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, Color.WHITE, activity)
    }

    override fun onFavoriteIconUpdated(isFavorite: Boolean, animate: Boolean) {
        if (_binding == null) return
        binding.quickFavorite.isSelected = isFavorite
        binding.quickFavorite.isActivated = isFavorite
        val icon = when {
            animate && isFavorite -> R.drawable.avd_favorite
            animate -> R.drawable.avd_unfavorite
            isFavorite -> R.drawable.ic_favorite
            else -> R.drawable.ic_favorite_border
        }
        binding.quickFavorite.setImageResource(icon)
        if (animate) {
            (binding.quickFavorite.drawable as? Animatable)?.start()
        }
    }

    override fun toolbarIconColor(): Int = Color.WHITE

    override fun onShow() {
        controlsFragment.show()
    }

    override fun onHide() {
        controlsFragment.hide()
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

}
