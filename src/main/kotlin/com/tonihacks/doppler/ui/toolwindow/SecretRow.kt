package com.tonihacks.doppler.ui.toolwindow

/**
 * One row in the secrets table. [value] is `var` so [SecretsTableModel.setValueAt]
 * can update in-place and keep the table index stable. Redacted [toString].
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
