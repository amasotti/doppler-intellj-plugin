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

    fun <R> map(transform: (T) -> R): DopplerResult<R> = when (this) {
        is Success -> runCatching { Success(transform(value)) }
            // Drop exception message — forwarding it could pipe a secret-bearing
            // value into Failure.error and on into a notification body.
            .getOrElse { Failure("Failed to process result") }
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> DopplerResult<R>): DopplerResult<R> = when (this) {
        is Success -> runCatching { transform(value) }
            .getOrElse { Failure("Failed to process result") }
        is Failure -> this
    }
}
