package com.tonihacks.doppler.ui.toolwindow

import com.tonihacks.doppler.DopplerBundle
import javax.swing.table.AbstractTableModel

/**
 * Table model for the Doppler secrets tool window.
 *
 * Columns:
 *  - 0 ([COL_NAME]) — secret key, read-only
 *  - 1 ([COL_VALUE]) — secret value, masked by default, editable
 *
 * ## Security
 *
 * [toString] never prints secret values. [getValueAt] for [COL_VALUE] returns
 * [MASKED_PLACEHOLDER] when the row is not revealed. Raw values are accessible
 * only through [rows] (for callers that need to read before a CLI save) — those
 * callers must not log the collection.
 */
class SecretsTableModel : AbstractTableModel() {

    companion object {
        const val COL_NAME = 0
        const val COL_VALUE = 1

        /** Placeholder shown in the Value cell when a row is not revealed. */
        const val MASKED_PLACEHOLDER = "••••••••"
    }

    private val _rows = mutableListOf<SecretRow>()

    /**
     * Snapshot of rows at the time of access. Callers must not log the value fields.
     *
     * Returns a defensive copy so external code cannot cast to [MutableList] and
     * corrupt the model's backing store without going through [setRows] / [setValueAt].
     * The [SecretRow] objects themselves are shared references — mutations to their
     * fields are visible in the model (intentional: allows [setValueAt] to work in-place).
     */
    val rows: List<SecretRow> get() = _rows.toList()

    /**
     * Replaces all rows with [newRows]. Clears any modified state from the previous
     * load. Fires [fireTableDataChanged] so bound [javax.swing.JTable]s repaint.
     */
    fun setRows(newRows: List<SecretRow>) {
        _rows.clear()
        _rows.addAll(newRows)
        fireTableDataChanged()
    }

    /** Returns `true` if any row has been edited since the last [setRows] call. */
    fun hasModifiedRows(): Boolean = _rows.any { it.modified }

    override fun getRowCount(): Int = _rows.size
    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = when (column) {
        COL_NAME -> DopplerBundle.message("toolwindow.column.name")
        COL_VALUE -> DopplerBundle.message("toolwindow.column.value")
        else -> ""
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = _rows[rowIndex]
        return when (columnIndex) {
            COL_NAME -> row.key
            COL_VALUE -> if (row.revealed) row.value else MASKED_PLACEHOLDER
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != COL_VALUE) return
        _rows[rowIndex].value = aValue?.toString() ?: ""
        _rows[rowIndex].modified = true
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    /**
     * A masked cell must NOT be editable. If the user double-clicks a masked cell,
     * the Swing editor populates with the [MASKED_PLACEHOLDER] string ("•••…") and
     * a stray Enter would commit that placeholder as the secret's new value — the
     * CLI would then save "•••…" to Doppler, destroying the original secret.
     * Editing requires the user to first reveal the row.
     */
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
        columnIndex == COL_VALUE && _rows[rowIndex].revealed

    /** Never prints secret values — returns a count-only summary. */
    override fun toString(): String = "[REDACTED x${_rows.size}]"
}
