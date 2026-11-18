package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.SourceHttpClient.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException

internal enum class SearchStatus { MATCHES, EMPTY, TIMEOUT, RATE_LIMITED, NETWORK, HTTP, RULE }

internal fun httpSearchFailure(exception: Exception): HttpStatusException? =
    generateSequence<Throwable>(exception) { it.cause }.take(8).filterIsInstance<HttpStatusException>().firstOrNull()

/** HTTP status can survive a wrapper from the rule engine. Keep only the category in feedback logs. */
internal fun searchFailure(exception: Exception): SearchStatus {
    val causes = generateSequence<Throwable>(exception) { it.cause }.take(8).toList()
    val http = httpSearchFailure(exception)
    return when {
        http?.code == 429 -> SearchStatus.RATE_LIMITED
        http != null -> SearchStatus.HTTP
        causes.any { it is SocketTimeoutException } -> SearchStatus.TIMEOUT
        causes.any { it is IOException } -> SearchStatus.NETWORK
        else -> SearchStatus.RULE
    }
}
