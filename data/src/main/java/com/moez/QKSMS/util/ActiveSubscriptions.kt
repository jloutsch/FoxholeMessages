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
package dev.octoshrimpy.quik.util

import dev.octoshrimpy.quik.compat.SubscriptionInfoCompat
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** The active SIM subscriptions now, then again each time they change. */
fun activeSubscriptions(subscriptionManager: SubscriptionManagerCompat): Flow<List<SubscriptionInfoCompat>> = callbackFlow {
    val listener = object : SubscriptionManagerCompat.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            trySend(subscriptionManager.activeSubscriptionInfoList)
        }
    }
    trySend(subscriptionManager.activeSubscriptionInfoList)
    subscriptionManager.addOnSubscriptionsChangedListener(listener)
    awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(listener) }
}
