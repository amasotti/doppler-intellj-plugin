package com.tonihacks.doppler.service

/** Thrown by [DopplerProjectService.fetchSecrets] when the CLI fails. Message is CLI stderr verbatim. */
class DopplerFetchException(override val message: String) : RuntimeException(message)
