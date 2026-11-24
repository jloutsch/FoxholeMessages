package dev.octoshrimpy.quik.feature.conversationinfo

import dev.octoshrimpy.quik.common.util.extensions.events
import com.bumptech.glide.Glide
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkAdapter
import dev.octoshrimpy.quik.common.base.QkViewHolder
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.databinding.ConversationInfoSettingsBinding
import dev.octoshrimpy.quik.databinding.ConversationMediaListItemBinding
import dev.octoshrimpy.quik.databinding.ConversationRecipientListItemBinding
import dev.octoshrimpy.quik.extensions.isVideo
import dev.octoshrimpy.quik.feature.conversationinfo.ConversationInfoItem.*
import javax.inject.Inject

class ConversationInfoAdapter @Inject constructor(
    private val context: Context,
    private val colors: Colors
) : QkAdapter<ConversationInfoItem, QkViewHolder>() {

    val recipientClicks = events<Long>()
    val recipientLongClicks = events<Long>()
    val themeClicks = events<Long>()
    val nameClicks = events<Unit>()
    val notificationClicks = events<Unit>()
    val backgroundImageClicks = events<Unit>()
    val backgroundImageLongClicks = events<Unit>()
    val markUnreadClicks = events<Unit>()
    val pinClicks = events<Unit>()
    val archiveClicks = events<Unit>()
    val blockClicks = events<Unit>()
    val deleteClicks = events<Unit>()
    val mediaClicks = events<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val binding = ConversationRecipientListItemBinding.inflate(inflater, parent, false)
                QkViewHolder(binding.root).apply {
                    itemView.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                        item?.value?.id?.let { recipientClicks.tryEmit(it) }
                    }

                    itemView.setOnLongClickListener {
                        val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                        item?.value?.id?.let { recipientLongClicks.tryEmit(it) }
                        true
                    }

                    binding.theme.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) as? ConversationInfoRecipient
                        item?.value?.id?.let { themeClicks.tryEmit(it) }
                    }
                }
            }

            1 -> {
                val binding = ConversationInfoSettingsBinding.inflate(inflater, parent, false)
                QkViewHolder(binding.root).apply {
                    binding.groupName.setOnClickListener { nameClicks.tryEmit(Unit) }
                    binding.notifications.setOnClickListener { notificationClicks.tryEmit(Unit) }
                    binding.background.setOnClickListener { backgroundImageClicks.tryEmit(Unit) }
                    binding.background.setOnLongClickListener {
                        backgroundImageLongClicks.tryEmit(Unit)
                        true
                    }
                    binding.markUnread.setOnClickListener { markUnreadClicks.tryEmit(Unit) }
                    binding.pin.setOnClickListener { pinClicks.tryEmit(Unit) }
                    binding.archive.setOnClickListener { archiveClicks.tryEmit(Unit) }
                    binding.block.setOnClickListener { blockClicks.tryEmit(Unit) }
                    binding.delete.setOnClickListener { deleteClicks.tryEmit(Unit) }
                }
            }

            2 -> {
                val binding = ConversationMediaListItemBinding.inflate(inflater, parent, false)
                QkViewHolder(binding.root).apply {
                    itemView.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) as? ConversationInfoMedia
                        item?.value?.id?.let { mediaClicks.tryEmit(it) }
                    }
                }
            }

            else -> throw IllegalStateException()
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ConversationInfoRecipient -> {
                val binding = ConversationRecipientListItemBinding.bind(holder.itemView)
                val recipient = item.value
                binding.avatar.setRecipient(recipient)

                binding.name.text = recipient.contact?.name ?: recipient.address

                binding.address.text = recipient.address
                binding.address.setVisible(recipient.contact != null)

                binding.add.setVisible(recipient.contact == null)

                val theme = colors.theme(recipient)
                binding.theme.setTint(theme.accent)
            }

            is ConversationInfoSettings -> {
                val binding = ConversationInfoSettingsBinding.bind(holder.itemView)
                binding.groupName.summary = item.name

                binding.notifications.isEnabled = !item.blocked

                binding.pin.title = context.getString(when (item.pinned) {
                    true -> R.string.main_menu_unpin
                    false -> R.string.main_menu_pin
                })

                binding.archive.isEnabled = !item.blocked
                binding.archive.title = context.getString(when (item.archived) {
                    true -> R.string.info_unarchive
                    false -> R.string.info_archive
                })

                binding.block.title = context.getString(when (item.blocked) {
                    true -> R.string.info_unblock
                    false -> R.string.info_block
                })

                binding.background.summary = context.getString(when (item.backgroundUri.isNotEmpty()) {
                    true -> R.string.info_background_set
                    false -> R.string.info_background_not_set
                })
            }

            is ConversationInfoMedia -> {
                val binding = ConversationMediaListItemBinding.bind(holder.itemView)
                val part = item.value

                Glide.with(context)
                        .load(part.getUri())
                        .fitCenter()
                        .into(binding.thumbnail)

                binding.video.isVisible = part.isVideo()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (data[position]) {
            is ConversationInfoRecipient -> 0
            is ConversationInfoSettings -> 1
            is ConversationInfoMedia -> 2
        }
    }

    override fun areItemsTheSame(old: ConversationInfoItem, new: ConversationInfoItem): Boolean {
        return when {
            old is ConversationInfoRecipient && new is ConversationInfoRecipient -> {
               old.value.id == new.value.id
            }

            old is ConversationInfoSettings && new is ConversationInfoSettings -> {
                true
            }

            old is ConversationInfoMedia && new is ConversationInfoMedia -> {
                old.value.id == new.value.id
            }

            else -> false
        }
    }

}
