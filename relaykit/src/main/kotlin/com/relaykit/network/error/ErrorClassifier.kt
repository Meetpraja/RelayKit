package com.relaykit.network.error

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.CancellationException

/** Type-based classification shared by RelayResult conversion and lifecycle events. */
internal object ErrorClassifier {
    fun classify(error: Throwable): ErrorType = when (unwrappedInterceptorFailure(error)) {
        is InterruptedIOException -> ErrorType.TIMEOUT
        is SerializationException -> ErrorType.PARSING
        is IOException -> ErrorType.NETWORK
        else -> ErrorType.UNKNOWN
    }

    /** OkHttp's async dispatcher can wrap non-IO interceptor exceptions in IOException.suppressed. */
    fun originalCause(error: Throwable): Throwable = unwrappedInterceptorFailure(error)

    fun cancellation(error: Throwable): CancellationException? =
        unwrappedInterceptorFailure(error) as? CancellationException

    private fun unwrappedInterceptorFailure(error: Throwable): Throwable = when (error) {
        is RelayInterceptorException -> error.cause?.let(::unwrappedInterceptorFailure) ?: error
        is IOException -> {
            val suppressed = error.suppressed.firstOrNull { it !is IOException }
            when {
                suppressed != null -> unwrappedInterceptorFailure(suppressed)
                error.cause is RelayInterceptorException -> unwrappedInterceptorFailure(error.cause!!)
                error.cause != null && error.cause !is IOException -> unwrappedInterceptorFailure(error.cause!!)
                else -> error
            }
        }
        else -> error
    }

    fun defaultMessage(type: ErrorType): String = when (type) {
        ErrorType.HTTP -> "The server returned an error"
        ErrorType.NETWORK -> "A network error occurred"
        ErrorType.TIMEOUT -> "The request timed out"
        ErrorType.PARSING -> "The response could not be parsed"
        ErrorType.UNKNOWN -> "The request failed"
        ErrorType.CANCELLED -> "The request was cancelled"
    }
}

/** Marks non-IO failures thrown by an application interceptor across OkHttp's async boundary. */
internal class RelayInterceptorException(cause: Throwable) : IOException("Application interceptor failed", cause)
