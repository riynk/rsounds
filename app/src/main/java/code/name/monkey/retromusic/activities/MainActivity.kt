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
package code.name.monkey.retromusic.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.contains
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.navigation.ui.setupWithNavController
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsCastActivity
import code.name.monkey.retromusic.fragments.settings.NowPlayingSettingsFragment
import code.name.monkey.retromusic.fragments.settings.OtherSettingsFragment
import code.name.monkey.retromusic.fragments.settings.PersonalizeSettingsFragment
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SearchQueryHelper.getSongs
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.model.CategoryInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.PlaylistSongsLoader
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.AppRater
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.logE
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

class MainActivity : AbsCastActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    companion object {
        const val TAG = "MainActivity"
        const val EXPAND_PANEL = "expand_panel"
        const val SETTINGS_SCREEN_TITLE = "settings_screen_title"
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        preferenceScreen: PreferenceScreen
    ): Boolean {
        val action = when (caller) {
            is PersonalizeSettingsFragment -> R.id.action_personalizeSettingsFragment_to_subscreen
            is NowPlayingSettingsFragment -> R.id.action_nowPlayingSettingsFragment_to_subscreen
            is OtherSettingsFragment -> R.id.action_otherSettingsFragment_to_subscreen
            else -> return false
        }
        val arguments = Bundle().apply {
            putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.key)
            putString(SETTINGS_SCREEN_TITLE, preferenceScreen.title?.toString())
        }
        NavHostFragment.findNavController(caller).navigate(action, arguments)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setOnExitAnimationListener { provider ->
            val density = resources.displayMetrics.density
            val brand = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                alpha = 0f
                addView(TextView(this@MainActivity).apply {
                    text = "RSOUNDS"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    letterSpacing = 0.18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "YOUR MUSIC • YOUR SOUND"
                    setTextColor(0xFFB8B8C2.toInt())
                    textSize = 10f
                    letterSpacing = 0.12f
                    gravity = Gravity.CENTER
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
            }
            (provider.view as? ViewGroup)?.addView(
                brand,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply { topMargin = (142 * density).toInt() }
            )
            // Android 16 can dispatch the splash exit callback after its icon view
            // has already been detached. Access the optional icon defensively.
            runCatching { provider.iconView }.getOrNull()?.animate()
                ?.alpha(0f)
                ?.rotation(10f)
                ?.scaleX(1.12f)
                ?.scaleY(1.12f)
                ?.setDuration(620L)
                ?.start()
            brand.animate()
                .alpha(1f)
                .translationY((-6 * density))
                .setDuration(260L)
                .withEndAction {
                    brand.animate()
                        .alpha(0f)
                        .setStartDelay(380L)
                        .setDuration(200L)
                        .withEndAction { provider.remove() }
                        .start()
                }
                .start()
        }
        super.onCreate(savedInstanceState)
        setTaskDescriptionColorAuto()
        hideStatusBar()
        updateTabs()
        AppRater.appLaunched(this)

        setupNavigationController()

        WhatsNewFragment.showChangeLog(this)
    }

    private fun setupNavigationController() {
        val navController = findNavController(R.id.fragment_container)
        val navInflater = navController.navInflater
        val navGraph = navInflater.inflate(R.navigation.main_graph)

        val categoryInfo: CategoryInfo = PreferenceUtil.libraryCategory.first { it.visible }
        if (categoryInfo.visible) {
            if (!navGraph.contains(PreferenceUtil.lastTab)) PreferenceUtil.lastTab =
                categoryInfo.category.id
            navGraph.setStartDestination(
                if (PreferenceUtil.rememberLastTab) {
                    PreferenceUtil.lastTab.let {
                        if (it == 0) {
                            categoryInfo.category.id
                        } else {
                            it
                        }
                    }
                } else categoryInfo.category.id
            )
        }
        navController.graph = navGraph
        navigationView.setupWithNavController(navController)
        // Scroll Fragment to top
        navigationView.setOnItemReselectedListener {
            currentFragment(R.id.fragment_container).apply {
                if (this is IScrollHelper) {
                    scrollToTop()
                }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == navGraph.startDestinationId) {
                currentFragment(R.id.fragment_container)?.enterTransition = null
            }
            when (destination.id) {
                R.id.action_home, R.id.action_song, R.id.action_album, R.id.action_artist, R.id.action_folder, R.id.action_playlist, R.id.action_genre, R.id.action_search -> {
                    // Save the last tab
                    if (PreferenceUtil.rememberLastTab) {
                        saveTab(destination.id)
                    }
                    // Show Bottom Navigation Bar
                    setBottomNavVisibility(visible = true, animate = true)
                }
                R.id.playing_queue_fragment -> {
                    setBottomNavVisibility(visible = false, hideBottomSheet = true)
                }
                else -> setBottomNavVisibility(
                    visible = false,
                    animate = true
                ) // Hide Bottom Navigation Bar
            }
        }
    }

    private fun saveTab(id: Int) {
        if (PreferenceUtil.libraryCategory.firstOrNull { it.category.id == id }?.visible == true) {
            PreferenceUtil.lastTab = id
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val expand = intent?.extra<Boolean>(EXPAND_PANEL)?.value ?: false
        if (expand && PreferenceUtil.isExpandPanel) {
            fromNotification = true
            slidingPanel.bringToFront()
            expandPanel()
            intent?.removeExtra(EXPAND_PANEL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        intent ?: return
        handlePlaybackIntent(intent)
    }

    @Suppress("deprecation")
    private fun handlePlaybackIntent(intent: Intent) {
        lifecycleScope.launch(IO) {
            val uri: Uri? = intent.data
            val mimeType: String? = intent.type
            var handled = false
            if (intent.action != null &&
                intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
            ) {
                val songs: List<Song> = getSongs(intent.extras!!)
                if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) {
                    MusicPlayerRemote.openAndShuffleQueue(songs, true)
                } else {
                    MusicPlayerRemote.openQueue(songs, 0, true)
                }
                handled = true
            }
            if (uri != null && uri.toString().isNotEmpty()) {
                MusicPlayerRemote.playFromUri(this@MainActivity, uri)
                handled = true
            } else if (MediaStore.Audio.Playlists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "playlistId", "playlist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = PlaylistSongsLoader.getPlaylistSongList(get(), id)
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            } else if (MediaStore.Audio.Albums.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "albumId", "album")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs = libraryViewModel.albumById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            } else if (MediaStore.Audio.Artists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "artistId", "artist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = libraryViewModel.artistById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            }
            if (handled) {
                setIntent(Intent())
            }
        }
    }

    private fun parseLongFromIntent(
        intent: Intent,
        longKey: String,
        stringKey: String,
    ): Long {
        var id = intent.getLongExtra(longKey, -1)
        if (id < 0) {
            val idString = intent.getStringExtra(stringKey)
            if (idString != null) {
                try {
                    id = idString.toLong()
                } catch (e: NumberFormatException) {
                    logE(e)
                }
            }
        }
        return id
    }
}
