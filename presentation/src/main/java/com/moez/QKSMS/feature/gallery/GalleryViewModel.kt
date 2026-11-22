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

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.moez.QKSMS.contentproviders.MmsPartProvider
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.interactor.SaveImage
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

class GalleryViewModel @Inject constructor(
    conversationRepo: ConversationRepository,
    @Named("partId") private val partId: Long,
    private val context: Context,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val saveImage: SaveImage,
    private val permissions: PermissionManager
) : QkViewModel<GalleryView, GalleryState>(GalleryState()) {
    init {
        // Paged over the tapped message's own attachments, not the whole thread's - swiping should
        // run to the end of this message and stop, rather than continuing into unrelated messages.
        // Read in the background (#71); newState's block runs on the main thread, so nothing is read inside it
        viewModelScope.launch(Dispatchers.IO) {
            val message = messageRepo.getMessageForPart(partId) ?: return@launch
            // An empty result here shows as a black screen with no counter and no crash,
            // so log the join keys - otherwise there's nothing to diagnose it from
            Timber.v("opening part $partId of message id=${message.id} contentId=${message.contentId}")
            val parts = messageRepo.getMediaPartsForMessage(message.id, message.contentId)
            val title = conversationRepo.getConversation(message.threadId)?.getTitle()
            newState { copy(parts = parts, title = title, date = message.date) }
        }
    }

    override fun bindView(view: GalleryView) {
        super.bindView(view)

        // The page on screen, which every menu action applies to; null until the first page shows
        var currentPart: MmsPart? = null
        view.pageChanged().collectIn(view.bindScope) { part -> currentPart = part }

        // When the screen is touched, toggle the visibility of the navigation UI
        view.screenTouched().collectIn(view.bindScope) {
            newState { copy(navigationVisible = !navigationVisible) }
        }

        view.optionsItemSelected().collectIn(view.bindScope) { itemId ->
            val part = currentPart ?: return@collectIn
            when (itemId) {
                // Save image to device
                R.id.save -> if (hasStorage(view)) {
                    saveImage.execute(part.id) { context.makeToast(R.string.gallery_toast_saved) }
                }

                // Save every picture/video belonging to the current message in one go, instead of
                // paging through them and saving one at a time
                R.id.save_all -> if (hasStorage(view)) {
                    val siblingIds = state.value.parts
                            ?.filter { it.messageId == part.messageId }
                            ?.map { it.id }
                            ?.takeIf { it.isNotEmpty() }
                            ?: listOf(part.id)

                    var remaining = siblingIds.size
                    siblingIds.forEach { id ->
                        saveImage.execute(id) {
                            remaining -= 1
                            if (remaining == 0) {
                                context.makeToast(context.resources.getQuantityString(
                                        R.plurals.gallery_toast_saved_all, siblingIds.size, siblingIds.size))
                            }
                        }
                    }
                }

                // Share image externally
                R.id.share -> navigator.shareFile(
                        MmsPartProvider().getUriForMmsPartId(context, part.id, part.getBestFilename()),
                        part.type
                )

                // message part context menu item selected - forward
                R.id.forward -> navigator.showCompose("", listOf(part.getUri()))

                // message part context menu item selected - open externally
                R.id.openExternally -> navigator.viewFile(
                        MmsPartProvider().getUriForMmsPartId(context, part.id, part.getBestFilename()),
                        part.type
                )
            }
        }
    }

    private fun hasStorage(view: GalleryView): Boolean =
            permissions.hasStorage().also { if (!it) view.requestStoragePermission() }

}
