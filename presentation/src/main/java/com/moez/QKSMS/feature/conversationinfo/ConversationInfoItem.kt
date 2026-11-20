package dev.octoshrimpy.quik.feature.conversationinfo

import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.Recipient

sealed class ConversationInfoItem {

    data class ConversationInfoRecipient(val value: Recipient) : ConversationInfoItem()

    data class ConversationInfoSettings(
        val name: String,
        val recipients: List<Recipient>,
        val archived: Boolean,
        val blocked: Boolean,
        val pinned: Boolean = false,
        val backgroundUri: String = ""
    ) : ConversationInfoItem()

    data class ConversationInfoMedia(val value: MmsPart) : ConversationInfoItem()

}
