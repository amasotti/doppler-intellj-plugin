package com.tonihacks.doppler.ui.toolwindow

import com.tonihacks.doppler.DopplerBundle
import javax.swing.table.AbstractTableModel

class SecretsTableModel : AbstractTableModel() {

    companion object {
        const val COL_NAME = 0
        const val COL_VALUE = 1

        const val MASKED_PLACEHOLDER = "••••••••"
    }

    private val _rows = mutableListOf<SecretRow>()

    /** Defensive copy so external code can't cast to MutableList and corrupt the backing store. */
    val rows: List<SecretRow> get() = _rows.toList()

    fun setRows(newRows: List<SecretRow>) {
        _rows.clear()
        _rows.addAll(newRows)
        fireTableDataChanged()
    }

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
     * Editing a masked cell is forbidden: the Swing editor would populate with the
     * placeholder string, and committing it would overwrite the real secret with `•••…`.
     */
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
        columnIndex == COL_VALUE && _rows[rowIndex].revealed

    override fun toString(): String = "[REDACTED x${_rows.size}]"
}
