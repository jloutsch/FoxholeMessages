/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.contacts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.util.extensions.textChanges
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.editorActions
import dev.octoshrimpy.quik.common.util.extensions.clicks
import androidx.lifecycle.lifecycleScope
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.ViewModelFactory
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.hideKeyboard
import dev.octoshrimpy.quik.common.util.extensions.showKeyboard
import dev.octoshrimpy.quik.common.widget.QkDialog
import dev.octoshrimpy.quik.databinding.ContactsActivityBinding
import dev.octoshrimpy.quik.feature.compose.editing.ComposeItem
import dev.octoshrimpy.quik.feature.compose.editing.ComposeItemAdapter
import dev.octoshrimpy.quik.feature.compose.editing.PhoneNumberAction
import dev.octoshrimpy.quik.feature.compose.editing.PhoneNumberPickerAdapter
import javax.inject.Inject

class ContactsActivity : QkThemedActivity(), ContactsContract {

    companion object {
        const val SHARING_KEY = "sharing"
        const val CHIPS_KEY = "chips"
    }

    private lateinit var binding: ContactsActivityBinding

    @Inject lateinit var contactsAdapter: ComposeItemAdapter
    @Inject lateinit var phoneNumberAdapter: PhoneNumberPickerAdapter
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject lateinit var navigator: Navigator

    override val bindScope get() = lifecycleScope

    override val queryChangedIntent: Flow<CharSequence> by lazy { binding.search.textChanges() }
    override val queryClearedIntent: Flow<Unit> by lazy { binding.cancel.clicks() }
    override val queryEditorActionIntent: Flow<Int> by lazy { binding.search.editorActions() }
    override val composeItemPressedIntent: Flow<ComposeItem> by lazy { contactsAdapter.clicks }
    override val composeItemLongPressedIntent: Flow<ComposeItem> by lazy { contactsAdapter.longClicks }
    override val phoneNumberSelectedIntent: StateFlow<Long?> by lazy { phoneNumberAdapter.selectedItemChanges }
    override val phoneNumberActionIntent = events<PhoneNumberAction>()

    private val viewModel by lazy { ViewModelProvider(this, viewModelFactory)[ContactsViewModel::class.java] }

    private val phoneNumberDialog by lazy {
        QkDialog(this).apply {
            titleRes = R.string.compose_number_picker_title
            adapter = phoneNumberAdapter
            positiveButton = R.string.compose_number_picker_always
            positiveButtonListener = { phoneNumberActionIntent.tryEmit(PhoneNumberAction.ALWAYS) }
            negativeButton = R.string.compose_number_picker_once
            negativeButtonListener = { phoneNumberActionIntent.tryEmit(PhoneNumberAction.JUST_ONCE) }
            cancelListener = { phoneNumberActionIntent.tryEmit(PhoneNumberAction.CANCEL) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = ContactsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        viewModel.bindView(this)

        binding.contacts.adapter = contactsAdapter

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigator.showMainActivity()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun render(state: ContactsState) {
        binding.cancel.isVisible = state.query.length > 1

        contactsAdapter.data = state.composeItems

        if (state.selectedContact != null && !phoneNumberDialog.isShowing) {
            phoneNumberAdapter.data = state.selectedContact.numbers
            phoneNumberDialog.subtitle = state.selectedContact.name
            phoneNumberDialog.show()
        } else if (state.selectedContact == null && phoneNumberDialog.isShowing) {
            phoneNumberDialog.dismiss()
        }
    }

    override fun clearQuery() {
        binding.search.text = null
    }

    override fun openKeyboard() {
        binding.search.postDelayed({
            binding.search.showKeyboard()
        }, 200)
    }

    override fun finish(result: HashMap<String, String?>) {
        binding.search.hideKeyboard()
        val intent = Intent().putExtra(CHIPS_KEY, result)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

}
