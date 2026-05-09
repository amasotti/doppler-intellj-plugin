package com.tonihacks.doppler.cli

data class DopplerProject(
    val id: String,
    val name: String,
    val slug: String,
)

data class DopplerConfig(
    val name: String,
    val project: String,
    val environment: String,
)

data class DopplerUser(
    val email: String,
    val name: String,
)

sealed class DopplerResult<out T> {
    data class Success<T>(val value: T) : DopplerResult<T>()
    data class Failure(val error: String, val exitCode: Int = -1) : DopplerResult<Nothing>()

    /**
     * Transforms the success value using the provided [transform] function.
     *
     * If this is [Success], it returns a new [DopplerResult] containing the transformed value.
     * If [transform] throws an exception, it is caught and returned as a [Failure].
     * If this is [Failure], the error is propagated unchanged.
     */
    fun <R> map(transform: (T) -> R): DopplerResult<R> = when (this) {
        is Success -> runCatching { Success(transform(value)) }
            // Fixed (Phase 8 carry-forward from Phase 7 review): was `"... ${it.message}"` which
            // forwards arbitrary exception messages into Failure.error. A future transform that
            // receives a secret value could pipe it into DopplerFetchException.message via this path.
            .getOrElse { Failure("Failed to process result") }
        is Failure -> this
    }

    /**
     * Transforms the success value into another [DopplerResult] using the provided [transform] function.
     *
     * This is used to chain multiple operations that return a [DopplerResult], avoiding
     * nested structures. If [transform] throws an exception, it returns a [Failure].
     * If this is [Failure], the error is propagated unchanged.
     */
    fun <R> flatMap(transform: (T) -> DopplerResult<R>): DopplerResult<R> = when (this) {
        is Success -> runCatching { transform(value) }
            // Same hardening as map — fixed string, no exception message forwarding.
            .getOrElse { Failure("Failed to process result") }
        is Failure -> this
    }
}
