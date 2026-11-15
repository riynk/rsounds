package code.name.monkey.retromusic.fragments.player

import android.content.SharedPreferences
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.LYRICS_TYPE
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.databinding.FragmentCoverLyricsBinding
import code.name.monkey.retromusic.fragments.NowPlayingScreen
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.fragments.base.goToLyrics
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.KaraokeLyricsView
import code.name.monkey.retromusic.lyrics.LyricsDisplaySettings
import code.name.monkey.retromusic.lyrics.LyricsDocumentLoader
import code.name.monkey.retromusic.lyrics.LyricsSurface
import code.name.monkey.retromusic.lyrics.LyricsPreferences
import code.name.monkey.retromusic.util.CoverLyricsType
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CoverLyricsFragment : AbsMusicServiceFragment(R.layout.fragment_cover_lyrics),
    MusicProgressViewUpdateHelper.Callback, SharedPreferences.OnSharedPreferenceChangeListener {

    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var _binding: FragmentCoverLyricsBinding? = null
    private val binding get() = _binding!!
    private val lyricsView: KaraokeLyricsView get() = binding.lyricsView
    private var lyricsLoadJob: Job? = null
    private var lyricsLoadRequest = 0
    private var artworkBoundsRoot: View? = null
    private var artworkBoundsObserver: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var dimmedArtworkView: ImageView? = null
    private var originalArtworkColorFilter: ColorFilter? = null
    private var appliedArtworkColorFilter: ColorFilter? = null
    private var appliedArtworkDimPercent = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCoverLyricsBinding.bind(view)
        // Over Cover is rendered directly on the artwork. These views have no background drawable.
        binding.root.background = null
        binding.playerLyrics.background = null
        binding.lyricsView.background = null
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 33, 500)
        attachArtworkBoundsTracking()

        lyricsView.apply {
            val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val lineCount = try {
                preferences.getString("lyrics_over_line_count", "3")?.toIntOrNull() ?: 3
            } catch (_: ClassCastException) {
                // Older builds may have stored this preference as an integer.
                val legacyValue = try {
                    preferences.getInt("lyrics_over_line_count", 3)
                } catch (_: ClassCastException) {
                    3
                }
                preferences.edit()
                    .putString("lyrics_over_line_count", legacyValue.toString())
                    .apply()
                legacyValue
            }
            setCompactLineCount(lineCount.coerceIn(1, 4))
            setCompactMode(true)
            setStyle(LyricsDisplaySettings.read(requireContext(), LyricsSurface.OVER_COVER))
            setOnLineClickListener { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
            }
            setOnClickListener { goToLyrics(requireActivity()) }
            setOnTranslationRetryListener { updateLyrics() }
        }

        val showOverCoverLyrics = shouldShowOverCoverLyrics()
        setLyricsOverlayVisible(showOverCoverLyrics)
        if (showOverCoverLyrics) {
            progressViewUpdateHelper?.start()
            updateLyrics()
        }
    }

    fun setColors(color: MediaNotificationProcessor) {
        if (_binding == null) return
        lyricsView.setTextColors(color.primaryTextColor, color.secondaryTextColor)
        updateLyricsBounds()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when {
            key == SHOW_LYRICS || key == LYRICS_TYPE -> {
                val enabled = sharedPreferences.getBoolean(SHOW_LYRICS, false) &&
                    PreferenceUtil.lyricsType == CoverLyricsType.OVER_COVER
                setLyricsOverlayVisible(enabled)
                if (enabled) {
                    progressViewUpdateHelper?.start()
                    updateLyrics()
                } else {
                    lyricsLoadRequest++
                    lyricsLoadJob?.cancel()
                    progressViewUpdateHelper?.stop()
                    lyricsView.clearDocument()
                }
            }
            key?.startsWith("lyrics_") == true -> {
                if (shouldShowOverCoverLyrics()) {
                    lyricsView.setStyle(
                        LyricsDisplaySettings.read(requireContext(), LyricsSurface.OVER_COVER)
                    )
                    if (key == "lyrics_over_line_count") {
                        val lineCount = try {
                            sharedPreferences.getString("lyrics_over_line_count", "3")
                                ?.toIntOrNull() ?: 3
                        } catch (_: ClassCastException) {
                            sharedPreferences.getInt("lyrics_over_line_count", 3)
                        }
                        lyricsView.setCompactLineCount(lineCount.coerceIn(1, 4))
                    }
                    updateLyrics()
                    updateLyricsBounds()
                }
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        if (shouldShowOverCoverLyrics()) updateLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (shouldShowOverCoverLyrics()) updateLyrics()
    }

    private fun shouldShowOverCoverLyrics(): Boolean =
        PreferenceUtil.showLyrics && PreferenceUtil.lyricsType == CoverLyricsType.OVER_COVER

    private fun setLyricsOverlayVisible(visible: Boolean) {
        // The FragmentContainerView can be visible while its child artwork overlay
        // remains GONE from the XML default. Both levels must be made visible.
        binding.root.isVisible = visible
        binding.playerLyrics.isVisible = visible
        if (!visible) {
            restoreArtworkDimming()
            binding.playerLyrics.alpha = 0f
        } else {
            // This fragment is a sibling of the artwork ViewPager's fading-edge
            // layer. Keep the Over Cover lyrics container above that layer so the
            // user sees timed lyrics directly over the album artwork.
            (binding.root.parent as? View)?.bringToFront()
            binding.playerLyrics.alpha = 1f
            binding.root.post { updateLyricsBounds() }
        }
    }

    /** Called when ViewPager selects another artwork page. */
    fun refreshArtworkEffects() {
        if (_binding != null) updateLyricsBounds()
    }

    private fun updateArtworkDimming(artwork: ImageView?) {
        val dimPercent = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getInt("lyrics_over_artwork_dim", 45)
            .coerceIn(0, 80)
        val target = artwork?.takeIf {
            shouldShowOverCoverLyrics() && it.isShown && dimPercent > 0
        }
        if (target == null) {
            restoreArtworkDimming()
            return
        }

        if (dimmedArtworkView !== target) {
            restoreArtworkDimming()
            dimmedArtworkView = target
            originalArtworkColorFilter = target.colorFilter
        }

        if (appliedArtworkDimPercent != dimPercent) {
            val brightness = 1f - (dimPercent / 100f)
            val filter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setScale(brightness, brightness, brightness, 1f)
                }
            )
            // Apply the filter to the artwork only, never to the lyric surface.
            target.colorFilter = filter
            appliedArtworkColorFilter = filter
            appliedArtworkDimPercent = dimPercent
        }
    }

    private fun restoreArtworkDimming() {
        val artwork = dimmedArtworkView ?: return
        if (artwork.colorFilter === appliedArtworkColorFilter) {
            artwork.colorFilter = originalArtworkColorFilter
        }
        dimmedArtworkView = null
        originalArtworkColorFilter = null
        appliedArtworkColorFilter = null
        appliedArtworkDimPercent = -1
    }

    private fun attachArtworkBoundsTracking() {
        val watchView = parentFragment?.view ?: binding.root.rootView
        artworkBoundsRoot = watchView
        val observer = ViewTreeObserver.OnGlobalLayoutListener {
            updateLyricsBounds()
        }
        artworkBoundsObserver = observer
        watchView.viewTreeObserver.addOnGlobalLayoutListener(observer)
        binding.root.post { updateLyricsBounds() }
    }

    private fun findCurrentArtworkView(): View? {
        val owner = when (val parent = parentFragment) {
            is PlayerAlbumCoverFragment -> parent
            else -> parent?.childFragmentManager
                ?.findFragmentById(R.id.playerAlbumCoverFragment) as? PlayerAlbumCoverFragment
        }
        return owner?.currentArtworkView()
            ?: parentFragment?.view?.findViewById(R.id.player_image)
            ?: binding.root.rootView.findViewById(R.id.player_image)
    }

    /**
     * Over Cover must use the actual player_image bounds. The host player slot
     * is often taller than the square artwork, so filling the slot lets lyrics
     * escape above and below the cover.
     */
    private fun updateLyricsBounds() {
        if (_binding == null) return
        val artwork = findCurrentArtworkView() as? ImageView
        if (artwork == null || artwork.width <= 0 || artwork.height <= 0) {
            updateArtworkDimming(null)
            // Never fall back to the full player slot: Over Cover belongs only
            // inside the bounds of a real, laid-out album artwork view.
            binding.playerLyrics.isVisible = false
            return
        }
        updateArtworkDimming(artwork)

        val rootLocation = IntArray(2)
        val artworkLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        artwork.getLocationOnScreen(artworkLocation)

        val left = artworkLocation[0] - rootLocation[0]
        val top = artworkLocation[1] - rootLocation[1]
        val params = binding.playerLyrics.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(artwork.width, artwork.height)
        val changed = params.width != artwork.width ||
            params.height != artwork.height ||
            params.leftMargin != left ||
            params.topMargin != top

        if (changed) {
            params.width = artwork.width
            params.height = artwork.height
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = left
            params.topMargin = top
            binding.playerLyrics.layoutParams = params
        }

        binding.playerLyrics.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = 12f * resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        binding.playerLyrics.clipToOutline = true
        binding.playerLyrics.clipChildren = true
        binding.playerLyrics.clipToPadding = true
        binding.playerLyrics.isVisible = shouldShowOverCoverLyrics()
    }

    private fun updateLyrics() {
        val currentSong = MusicPlayerRemote.currentSong
        val requestId = ++lyricsLoadRequest
        lyricsLoadJob?.cancel()

        // Do not keep the previous track's timed lines attached to the new song.
        lyricsView.clearDocument()
        val context = requireContext()
        val translationRequested =
            LyricsPreferences.isTranslationEnabled(context, currentSong)
        lyricsView.setTranslationStatus(
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
            lyricsView.setStyle(
                LyricsDisplaySettings.read(context, LyricsSurface.OVER_COVER)
            )

            if (document == null) {
                lyricsView.setTranslationStatus(null)
                lyricsView.setEmptyMessage(getString(R.string.no_lyrics_found))
                return@launch
            }

            val showWordHighlight = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("lyrics_word_highlight", true)
            // TTML/LRC is visible right away; translation is a separate slower step.
            lyricsView.setDocument(document, null, showWordHighlight)

            if (!translationRequested) {
                lyricsView.setTranslationStatus(null)
                return@launch
            }

            try {
                val translations = LyricsDocumentLoader.translate(
                    context,
                    currentSong,
                    document
                )
                if (!isCurrentLyricsRequest(requestId, currentSong.id)) return@launch
                lyricsView.setDocument(document, translations, showWordHighlight)
                lyricsView.setTranslationStatus(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentLyricsRequest(requestId, currentSong.id)) {
                    lyricsView.setTranslationStatus(
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
        lyricsView.updateTime(progress.toLong())
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        if (shouldShowOverCoverLyrics()) {
            setLyricsOverlayVisible(true)
            progressViewUpdateHelper?.start()
            updateLyrics()
        } else {
            setLyricsOverlayVisible(false)
            progressViewUpdateHelper?.stop()
        }
        updateLyricsBounds()
    }

    override fun onPause() {
        super.onPause()
        progressViewUpdateHelper?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        lyricsLoadRequest++
        lyricsLoadJob?.cancel()
        progressViewUpdateHelper?.stop()
        restoreArtworkDimming()
        artworkBoundsObserver?.let { observer ->
            artworkBoundsRoot?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(observer)
        }
        artworkBoundsObserver = null
        artworkBoundsRoot = null
        _binding = null
    }
}
