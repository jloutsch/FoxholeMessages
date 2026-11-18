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
package dev.octoshrimpy.quik.receiver

/**
 * What an SMS delivery report says. Android always delivers the report with result OK and puts the
 * real outcome in the report's PDU (SmsDispatchersController.triggerDeliveryIntent), so the PDU's
 * status has to be read; the result code alone made every report look like "Delivered".
 * Interpretation follows the same AOSP class.
 */
enum class DeliveryReportStatus {
    DELIVERED, PENDING, FAILED;

    companion object {
        /** [status] is SmsMessage.getStatus() for a report in [format] ("3gpp" or "3gpp2"). */
        fun of(format: String?, status: Int): DeliveryReportStatus =
            if (format == "3gpp2") {
                // error class in bits 25-24: 0 none, 2 temporary (still trying), otherwise failed
                when ((status shr 24) and 0x03) {
                    0 -> DELIVERED
                    2 -> PENDING
                    else -> FAILED
                }
            } else {
                // GSM TP-Status: 0x00-0x1F completed, 0x20-0x3F still trying, 0x40+ failed
                when {
                    status >= 0x40 -> FAILED
                    status >= 0x20 -> PENDING
                    status >= 0 -> DELIVERED
                    else -> FAILED
                }
            }
    }
}
