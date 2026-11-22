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

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import dev.octoshrimpy.quik.repository.SenderCategoryRuleRepository
import javax.inject.Inject

class SenderRulesPresenter @Inject constructor(
    private val senderRuleRepo: SenderCategoryRuleRepository
) : QkFlowPresenter<SenderRulesView, SenderRulesState>(SenderRulesState()) {

    init {
        senderRuleRepo.getRules().collectIn(presenterScope) { list -> newState { copy(rules = list) } }
    }

    override fun bindIntents(view: SenderRulesView) {
        super.bindIntents(view)

        view.removeRule().collectIn(view.bindScope) { rule ->
            withContext(Dispatchers.IO) { senderRuleRepo.removeRule(rule.id) }
        }

        // The rule whose category is being changed: the last one tapped (what withLatestFrom read)
        var editing: SenderCategoryRule? = null
        view.editRule().collectIn(view.bindScope) { rule ->
            editing = rule
            view.showEditDialog(rule)
        }

        view.categorySelected().collectIn(view.bindScope) { category ->
            val rule = editing ?: return@collectIn
            withContext(Dispatchers.IO) { senderRuleRepo.setRule(rule.address, category) }
        }
    }

}
