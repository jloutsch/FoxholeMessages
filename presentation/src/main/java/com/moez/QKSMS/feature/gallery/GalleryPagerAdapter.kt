/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.gallery

import dev.octoshrimpy.quik.common.util.extensions.events
import com.bumptech.glide.Glide
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.mms.ContentType
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkListAdapter
import dev.octoshrimpy.quik.common.base.QkViewHolder
import dev.octoshrimpy.quik.databinding.GalleryImagePageBinding
import dev.octoshrimpy.quik.databinding.GalleryVideoPageBinding
import dev.octoshrimpy.quik.extensions.isImage
import dev.octoshrimpy.quik.extensions.isVideo
import dev.octoshrimpy.quik.model.MmsPart
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class GalleryPagerAdapter @Inject constructor(private val context: Context) : QkListAdapter<MmsPart, QkViewHolder>(itemId = { it.id }) {

    companion object {
        private const val VIEW_TYPE_INVALID = 0
        private const val VIEW_TYPE_IMAGE = 1
        private const val VIEW_TYPE_VIDEO = 2
    }

    val clicks = events<Unit>()

    private val contentResolver = context.contentResolver
    private val exoPlayers = mutableSetOf<ExoPlayer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return QkViewHolder(when (viewType) {
            VIEW_TYPE_IMAGE -> {
                val binding = GalleryImagePageBinding.inflate(inflater, parent, false)
                // When calling the public setter, it doesn't allow the midscale to be the same as the
                // maxscale or the minscale. We don't want 3 levels and we don't want to modify the library
                // so let's celebrate the invention of reflection!
                binding.image.attacher.run {
                    javaClass.getDeclaredField("mMinScale").run {
                        isAccessible = true
                        setFloat(binding.image.attacher, 1f)
                    }
                    javaClass.getDeclaredField("mMidScale").run {
                        isAccessible = true
                        setFloat(binding.image.attacher, 1f)
                    }
                    javaClass.getDeclaredField("mMaxScale").run {
                        isAccessible = true
                        setFloat(binding.image.attacher, 3f)
                    }
                }
                binding.root
            }

            VIEW_TYPE_VIDEO -> inflater.inflate(R.layout.gallery_video_page, parent, false)

            else -> inflater.inflate(R.layout.gallery_invalid_page, parent, false)

        }.apply { setOnClickListener { clicks.tryEmit(Unit) } })
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val part = getItem(position) ?: return
        when (getItemViewType(position)) {
            VIEW_TYPE_IMAGE -> {
                val binding = GalleryImagePageBinding.bind(holder.itemView)
                // We need to explicitly request a gif from glide for animations to work
                when (part.getUri().let(contentResolver::getType)) {
                    ContentType.IMAGE_GIF -> Glide.with(context)
                            .asGif()
                            .load(part.getUri())
                            .into(binding.image)

                    else -> Glide.with(context)
                            .asBitmap()
                            .load(part.getUri())
                            .into(binding.image)
                }
            }

            VIEW_TYPE_VIDEO -> {
                val binding = GalleryVideoPageBinding.bind(holder.itemView)
                releasePlayer(binding)
                // A single local MMS video file has no alternate-quality renditions to adapt
                // between, so there's nothing for a custom track selector to do here - just
                // build a plain player and hand it a MediaItem, which resolves content:// URIs
                // through ExoPlayer's own default media source factory.
                val exoPlayer = ExoPlayer.Builder(context).build()
                binding.video.player = exoPlayer
                exoPlayers.add(exoPlayer)

                // In-app playback of a since-confirmed-valid video (plays fine externally) was
                // still failing with no visible error - ExoPlayer had no error listener at all,
                // so a PlaybackException would fail completely silently. Logging it now instead
                // of guessing again what's different about this app's own player setup.
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "ExoPlayer error playing part ${part.id} (${part.type})")
                    }
                })

                exoPlayer.setMediaItem(MediaItem.fromUri(part.getUri()))
                exoPlayer.prepare()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val part = getItem(position)
        return when {
            part?.isImage() == true -> VIEW_TYPE_IMAGE
            part?.isVideo() == true -> VIEW_TYPE_VIDEO
            else -> VIEW_TYPE_INVALID
        }
    }

    override fun onViewRecycled(holder: QkViewHolder) {
        super.onViewRecycled(holder)
        if (holder.itemViewType == VIEW_TYPE_VIDEO) releasePlayer(GalleryVideoPageBinding.bind(holder.itemView))
    }

    // Each bind builds a new player, so the old one is released here rather than only at exit
    private fun releasePlayer(binding: GalleryVideoPageBinding) {
        (binding.video.player as? ExoPlayer)?.let { exoPlayer ->
            exoPlayer.release()
            exoPlayers.remove(exoPlayer)
        }
        binding.video.player = null
    }

    fun destroy() {
        exoPlayers.forEach { exoPlayer -> exoPlayer.release() }
        exoPlayers.clear()
    }

}
