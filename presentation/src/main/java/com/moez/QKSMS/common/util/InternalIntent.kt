/*
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import android.content.Intent
import java.util.UUID

/**
 * Tells this app's own requests apart from ones sent by other apps.
 *
 * The compose screen is exported (other apps share into it), so anything in its intent may come from
 * another app, and attachments from outside are checked by isSafeToAttachFromOtherApp. Forward and
 * "edit scheduled message" pass this app's own attachment links, which that check rightly refuses
 * from outsiders. Marking our own intents with a value generated fresh in this process, which no
 * other app can know, lets those through without loosening the check for everyone else.
 */
object InternalIntent {

    private const val EXTRA_TOKEN = "dev.octoshrimpy.quik.internalToken"
    private val token = UUID.randomUUID().toString()

    fun mark(intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, token)

    fun isInternal(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_TOKEN) == token
}
