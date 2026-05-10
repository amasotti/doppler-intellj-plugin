package com.tonihacks.doppler

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

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

    /**
     * Returns a [Supplier] that resolves the bundle string lazily on each call.
     *
     * Use this for [com.intellij.openapi.actionSystem.AnAction] constructor arguments
     * — IntelliJ recommends lazy resolution so the bundle isn't forced during static
     * action registration (matters for plugin-load performance and dynamic reloads).
     */
    @JvmStatic
    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): Supplier<String> = getLazyMessage(key, *params)
}
