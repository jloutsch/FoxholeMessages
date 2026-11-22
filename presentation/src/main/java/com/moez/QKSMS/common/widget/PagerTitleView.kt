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
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // flatMapLatest: stable in practice

package dev.octoshrimpy.quik.common.widget

import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.forEach
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.databinding.TabViewBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.repository.ConversationRepository
import javax.inject.Inject

class PagerTitleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    @Inject lateinit var colors: Colors
    @Inject lateinit var conversationRepo: ConversationRepository

    private val recipientId = MutableStateFlow<Long?>(null)

    var pager: ViewPager? = null
        set(value) {
            if (field !== value) {
                field = value
                recreate()
            }
        }

    init {
        if (!isInEditMode) appComponent.inject(this)
    }

    fun setRecipientId(id: Long) {
        recipientId.value = id
    }

    private fun recreate() {
        removeAllViews()

        pager?.adapter?.count?.forEach { position ->
            val view = TabViewBinding.inflate(LayoutInflater.from(context), this, false)
            view.label.text = pager?.adapter?.getPageTitle(position)
            view.root.setOnClickListener { pager?.currentItem = position }

            addView(view.root)
        }

        childCount.forEach { index ->
            getChildAt(index).isActivated = index == pager?.currentItem
        }

        pager?.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                childCount.forEach { index ->
                    getChildAt(index).isActivated = index == position
                }
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val states = arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(-android.R.attr.state_activated))

        // Runs until the view leaves the window
        attachedJob?.cancel()
        attachedJob = recipientId
                .filterNotNull()
                .flatMapLatest { recipientId ->
                    val recipient = withContext(Dispatchers.IO) { conversationRepo.getRecipient(recipientId) }
                    colors.themeChanges(recipient)
                }
                .map { theme ->
                    val textSecondary = context.resolveThemeColor(android.R.attr.textColorSecondary)
                    ColorStateList(states, intArrayOf(theme.theme, textSecondary))
                }
                .onEach { colorStateList ->
                    childCount.forEach { index ->
                        (getChildAt(index) as? TextView)?.setTextColor(colorStateList)
                    }
                }
                .launchIn(MainScope())
    }

    private var attachedJob: Job? = null

    override fun onDetachedFromWindow() {
        attachedJob?.cancel()
        attachedJob = null
        super.onDetachedFromWindow()
    }

}
