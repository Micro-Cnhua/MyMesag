package com.mymesag.model

import kotlinx.serialization.Serializable

@Serializable
data class MissedCallReplyEvent(
    val v: Int = 1,
    val type: String = "missed_call_reply",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val phoneNumber: String,
    val contactName: String,
    val text: String
)
