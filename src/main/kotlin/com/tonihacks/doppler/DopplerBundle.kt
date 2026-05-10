package com.tonihacks.doppler

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.DopplerBundle"

object DopplerBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)

    /** Lazy variant; required for AnAction constructor args (avoids forcing the bundle at registration). */
    @JvmStatic
    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): Supplier<String> = getLazyMessage(key, *params)
}
