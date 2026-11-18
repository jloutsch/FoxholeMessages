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

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The QRF Reply popup opens a screen while the app is in the background. Android 10+ blocks that
 * (it only logs "Abort background activity starts") unless the app may "display over other apps".
 * Verified on a Note 10: without it the popup appeared only within seconds of the app being shown.
 */
object OverlayPermission {

    /** True when the popup can't appear until the user allows "Display over other apps". */
    fun needed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
}
