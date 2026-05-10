package com.tonihacks.doppler.service

/**
 * Thrown by [DopplerProjectService.fetchSecrets] when the underlying CLI call fails.
 *
 * **Message contract:** The message is the Doppler CLI's `stderr.trim()` propagated verbatim from
 * `DopplerCliClient`.
 */
class DopplerFetchException(override val message: String) : RuntimeException(message)
