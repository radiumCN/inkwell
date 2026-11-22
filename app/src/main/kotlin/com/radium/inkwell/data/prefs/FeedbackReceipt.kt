package com.radium.inkwell.data.prefs

import kotlinx.serialization.Serializable

@Serializable
data class FeedbackReceipt(val issue: Int, val url: String, val title: String, val replyToken: String? = null)
