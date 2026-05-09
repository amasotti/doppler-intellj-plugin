package com.tonihacks.doppler

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.DopplerBundle"

/**
 * Accessor for `messages/DopplerBundle.properties`.
 *
 * Use [message] to retrieve localised strings. All keys are annotated with
 * `@PropertyKey` so typos surface as compile-time warnings in the IDE.
 *
 * Extends [DynamicBundle] (not the older `AbstractBundle`) so the bundle is
 * reloadable without IDE restart — required for dynamic plugin support.
 */
object DopplerBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
