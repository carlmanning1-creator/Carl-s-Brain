package com.carlmanning.carlsbrain.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class ActionItem(val title: String, val bucket: String)
