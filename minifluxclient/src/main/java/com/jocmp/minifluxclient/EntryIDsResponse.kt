package com.jocmp.minifluxclient

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EntryIDsResponse(
    val total: Int? = null,
    val entry_ids: List<Long>? = null
)
