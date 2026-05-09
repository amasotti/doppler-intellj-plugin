package com.tonihacks.doppler.ui.toolwindow

/**
 * A single row in the Doppler secrets table.
 *
 * ## Security
 *
 * [toString] is overridden so that the auto-generated data-class representation is
 * suppressed — prevents accidental exposure of [value] through `log.debug("$row")`,
 * debugger watch expressions, or `toString()` calls in exception messages.
 *
 * [value] is intentionally a `var` so [SecretsTableModel.setValueAt] can update it
 * in-place without replacing the row object; this keeps the table index stable.
 */
data class SecretRow(
    val key: String,
    var value: String,
    var revealed: Boolean = false,
    var modified: Boolean = false,
) {
    override fun toString(): String =
        "SecretRow(key='$key', value=[REDACTED], revealed=$revealed, modified=$modified)"
}
