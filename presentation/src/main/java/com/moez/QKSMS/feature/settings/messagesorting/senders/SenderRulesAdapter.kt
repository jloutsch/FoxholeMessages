/*
 * Copyright (C) 2026 Foxhole Messages contributors
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
package dev.octoshrimpy.quik.feature.settings.messagesorting.senders

import dev.octoshrimpy.quik.common.util.extensions.events
import android.view.LayoutInflater
import android.view.ViewGroup
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkListAdapter
import dev.octoshrimpy.quik.databinding.SenderRuleListItemBinding
import dev.octoshrimpy.quik.model.SenderCategoryRule

class SenderRulesAdapter : QkListAdapter<SenderCategoryRule, QkBindingViewHolder<SenderRuleListItemBinding>>(itemId = { it.id }) {

    val editRule = events<SenderCategoryRule>()
    val removeRule = events<SenderCategoryRule>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<SenderRuleListItemBinding> {
        val binding = SenderRuleListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            binding.root.setOnClickListener {
                val rule = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                editRule.tryEmit(rule)
            }
            binding.remove.setOnClickListener {
                val rule = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                removeRule.tryEmit(rule)
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<SenderRuleListItemBinding>, position: Int) {
        val item = getItem(position)!!
        holder.binding.address.text = item.address
        holder.binding.category.text = item.category
    }

}
