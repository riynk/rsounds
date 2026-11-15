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
package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ItemGenreBinding
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.interfaces.IGenreClickListener
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author Hemanth S (h4h13).
 */

class GenreAdapter(
    private val activity: FragmentActivity,
    var dataSet: List<Genre>,
    private val listener: IGenreClickListener
) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

    init {
        this.setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemGenreBinding.inflate(LayoutInflater.from(activity), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val genre = dataSet[position]
        holder.binding.title.text = genre.name
        holder.binding.text.text = String.format(
            Locale.getDefault(),
            "%d %s",
            genre.songCount,
            if (genre.songCount > 1) activity.getString(R.string.songs) else activity.getString(R.string.song)
        )
        loadGenreImage(genre, holder)
    }

    private fun loadGenreImage(genre: Genre, holder: GenreAdapter.ViewHolder) {
        holder.itemView.setTag(R.id.genre_artwork_tag, genre.id)
        val artworkViews = listOf(
            holder.binding.image,
            holder.binding.imageSecondary,
            holder.binding.imageTertiary
        )
        artworkViews.forEach {
            it.setImageDrawable(null)
            it.visibility = View.INVISIBLE
        }

        activity.lifecycleScope.launch {
            val distinctArtistSongs = withContext(Dispatchers.IO) {
                runCatching {
                    MusicUtil.songsByGenre(genre.id)
                        .asSequence()
                        .filter { it.artistId >= 0L && it.artistName.isNotBlank() }
                        .distinctBy { it.artistId }
                        .take(3)
                        .toList()
                }.getOrDefault(emptyList())
            }

            if (holder.itemView.getTag(R.id.genre_artwork_tag) != genre.id) return@launch
            distinctArtistSongs.forEachIndexed { index, song ->
                val target = artworkViews[index]
                target.visibility = View.VISIBLE
                Glide.with(activity)
                    .load(RetroGlideExtension.getSongModel(song))
                    .songCoverOptions(song)
                    .into(target)
            }
            val paletteSong = distinctArtistSongs.firstOrNull() ?: MusicUtil.songByGenre(genre.id)
            Glide.with(activity)
                .asBitmapPalette()
                .songCoverOptions(paletteSong)
                .load(RetroGlideExtension.getSongModel(paletteSong))
                .into(object : RetroMusicColoredTarget(holder.binding.image) {
                    override fun onColorReady(colors: MediaNotificationProcessor) {
                        if (holder.itemView.getTag(R.id.genre_artwork_tag) == genre.id) {
                            setColors(holder, colors)
                        }
                    }
                })
        }
        artworkViews.forEach { it.outlineProvider = ViewOutlineProvider.BOUNDS }
    }

    private fun setColors(holder: ViewHolder, color: MediaNotificationProcessor) {
        holder.binding.imageContainerCard.setCardBackgroundColor(color.backgroundColor)
        holder.binding.title.setTextColor(color.primaryTextColor)
        holder.binding.text.setTextColor(color.secondaryTextColor)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(list: List<Genre>) {
        dataSet = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemGenreBinding) : RecyclerView.ViewHolder(binding.root),
        View.OnClickListener {
        override fun onClick(v: View?) {
            listener.onClickGenre(dataSet[layoutPosition], itemView)
        }

        init {
            itemView.setOnClickListener(this)
        }
    }
}
