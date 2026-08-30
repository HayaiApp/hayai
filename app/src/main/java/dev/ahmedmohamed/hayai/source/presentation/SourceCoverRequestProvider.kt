package dev.ahmedmohamed.hayai.source.presentation

import okhttp3.Call
import okhttp3.Headers

/** Lets non-HttpSource implementations contribute their authenticated cover request. */
interface SourceCoverRequestProvider {
    val coverCallFactory: Call.Factory

    fun coverRequestHeaders(
        coverUrl: String,
        fallbackHeaders: Headers,
    ): Headers
}
