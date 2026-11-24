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
package dev.octoshrimpy.quik.feature.compose.part

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import android.content.Context
import androidx.core.view.isVisible
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkViewHolder
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.getDisplayName
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.MmsVcardListItemBinding
import dev.octoshrimpy.quik.extensions.isVCard
import dev.octoshrimpy.quik.feature.compose.BubbleUtils
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.util.tryOrNull
import ezvcard.Ezvcard
import javax.inject.Inject

class VCardBinder @Inject constructor(colors: Colors, private val context: Context) : PartBinder() {

    override val partLayout = R.layout.mms_vcard_list_item
    override var theme = colors.theme()

    override fun canBindPart(part: MmsPart) = part.isVCard()

    override fun bindPart(
        holder: QkViewHolder,
        part: MmsPart,
        message: Message,
        canGroupWithPrevious: Boolean,
        canGroupWithNext: Boolean
    ) {
        val binding = MmsVcardListItemBinding.bind(holder.itemView)
        BubbleUtils.getBubble(false, canGroupWithPrevious, canGroupWithNext, message.isMe())
                .let(binding.vCardBackground::setBackgroundResource)

        holder.itemView.setOnClickListener { onClick(part.id) }

        tryOrNull(true) {
            val uri = part.getUri()
            MainScope().launch {
                val displayName = withContext(Dispatchers.Default) {
                    tryOrNull(true) {
                        context.contentResolver.openInputStream(uri)
                            ?.use { inputStream -> Ezvcard.parse(inputStream).first() }
                            ?.let { vcard -> vcard.getDisplayName() ?: "" }
                    }
                } ?: return@launch
                binding.name.text = displayName
                binding.name.isVisible = displayName.isNotEmpty()
            }
        }

        if (!message.isMe()) {
            binding.vCardBackground.setBackgroundTint(theme.container)
            binding.vCardAvatar.setTint(theme.onContainer)
            binding.name.setTextColor(theme.onContainer)
            binding.label.setTextColor(theme.onContainer)
        } else {
            binding.vCardBackground.setBackgroundTint(holder.itemView.context.resolveThemeColor(R.attr.bubbleColor))
            binding.vCardAvatar.setTint(holder.itemView.context.resolveThemeColor(android.R.attr.textColorSecondary))
            binding.name.setTextColor(holder.itemView.context.resolveThemeColor(android.R.attr.textColorPrimary))
            binding.label.setTextColor(holder.itemView.context.resolveThemeColor(android.R.attr.textColorTertiary))
        }
    }

}
