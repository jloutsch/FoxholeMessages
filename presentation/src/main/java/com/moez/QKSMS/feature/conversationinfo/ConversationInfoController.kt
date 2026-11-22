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
package dev.octoshrimpy.quik.feature.conversationinfo

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkController
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.bluelinelabs.conductor.RouterTransaction
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.util.QkActivityResultContracts
import dev.octoshrimpy.quik.common.util.extensions.scrapViews
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.databinding.ConversationInfoControllerBinding
import dev.octoshrimpy.quik.feature.blocking.BlockingDialog
import dev.octoshrimpy.quik.feature.conversationinfo.injection.ConversationInfoModule
import dev.octoshrimpy.quik.feature.themepicker.ThemePickerController
import dev.octoshrimpy.quik.injection.appComponent
import javax.inject.Inject

class ConversationInfoController(
    val threadId: Long = 0
) : QkController<ConversationInfoControllerBinding, ConversationInfoView, ConversationInfoState, ConversationInfoPresenter>(), ConversationInfoView {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): ConversationInfoControllerBinding =
        ConversationInfoControllerBinding.inflate(inflater, container, false)

    @Inject override lateinit var presenter: ConversationInfoPresenter
    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var externalNavigator : ExternalNavigator
    @Inject lateinit var adapter: ConversationInfoAdapter

    private val nameDialog: TextInputDialog by lazy {
        TextInputDialog(activity!!, activity!!.getString(R.string.info_name), { nameChangeEvents.tryEmit(it) })
    }

    private val nameChangeEvents = events<String>()
    private val confirmDeleteEvents = events<Unit>()
    private val backgroundImageSelectedEvents = events<Uri>()

    private lateinit var openBackgroundImage: ActivityResultLauncher<QkActivityResultContracts.OpenDocumentParams>

    init {
        appComponent
                .conversationInfoBuilder()
                .conversationInfoModule(ConversationInfoModule(this))
                .build()
                .inject(this)
    }

    override fun onContextAvailable(context: Context) {
        openBackgroundImage = themedActivity!!
                .registerForActivityResult(QkActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != Uri.EMPTY) {
                        context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        backgroundImageSelectedEvents.tryEmit(uri)
                    }
                }
    }

    override fun onViewCreated() {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(GridSpacingItemDecoration(adapter, activity!!))
        binding.recyclerView.layoutManager = GridLayoutManager(activity, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (adapter.getItemViewType(position) == 2) 1 else 3
            }
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        // Redraw the rows when a recipient's color changes (QkThemedActivity is still RxJava: bridged)
        themedActivity?.theme?.collectIn(bindScope) { binding.recyclerView.scrapViews() }
        setTitle(R.string.info_title)
        showBackButton(true)
    }

    override fun render(state: ConversationInfoState) {
        if (state.hasError) {
            activity?.finish()
            return
        }

        adapter.data = state.data
    }

    override fun recipientClicks(): Flow<Long> = adapter.recipientClicks
    override fun recipientLongClicks(): Flow<Long> = adapter.recipientLongClicks
    override fun themeClicks(): Flow<Long> = adapter.themeClicks
    override fun nameClicks(): Flow<Unit> = adapter.nameClicks
    override fun nameChanges(): Flow<String> = nameChangeEvents
    override fun notificationClicks(): Flow<Unit> = adapter.notificationClicks
    override fun backgroundImageClicks(): Flow<Unit> = adapter.backgroundImageClicks
    override fun backgroundImageLongClicks(): Flow<Unit> = adapter.backgroundImageLongClicks
    override fun backgroundImageSelected(): Flow<Uri> = backgroundImageSelectedEvents
    override fun markUnreadClicks(): Flow<Unit> = adapter.markUnreadClicks
    override fun pinClicks(): Flow<Unit> = adapter.pinClicks
    override fun archiveClicks(): Flow<Unit> = adapter.archiveClicks
    override fun blockClicks(): Flow<Unit> = adapter.blockClicks
    override fun deleteClicks(): Flow<Unit> = adapter.deleteClicks
    override fun confirmDelete(): Flow<Unit> = confirmDeleteEvents
    override fun mediaClicks(): Flow<Long> = adapter.mediaClicks

    override fun showNameDialog(name: String) = nameDialog.setText(name).show()

    override fun showThemePicker(recipientId: Long) {
        router.pushController(RouterTransaction.with(ThemePickerController(recipientId))
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showBackgroundImagePicker() {
        openBackgroundImage.launch(QkActivityResultContracts.OpenDocumentParams(listOf("image/*")))
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(conversations, block)
    }

    override fun requestDefaultSms() {
        externalNavigator.showDefaultSmsDialog(activity!!)
    }

    override fun showDeleteDialog() {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources?.getQuantityString(R.plurals.dialog_delete_message, 1))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteEvents.tryEmit(Unit) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }
}
