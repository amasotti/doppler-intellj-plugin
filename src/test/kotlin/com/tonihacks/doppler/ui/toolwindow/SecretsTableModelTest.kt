package com.tonihacks.doppler.ui.toolwindow

import com.intellij.testFramework.junit5.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [SecretsTableModel] — data + masking logic.
 *
 * [TestApplication] is required because [SecretsTableModel.getColumnName] and the
 * visibility column values use [com.tonihacks.doppler.DopplerBundle], which needs
 * IntelliJ's classloader to resolve the bundle. No project fixture needed — the model
 * is a pure data class with no project-level services.
 */
@TestApplication
class SecretsTableModelTest {

    // ── initial state ──────────────────────────────────────────────────────────

    @Test
    fun `empty model has zero rows and two columns`() {
        val model = SecretsTableModel()
        assertThat(model.rowCount).isEqualTo(0)
        assertThat(model.columnCount).isEqualTo(2)
    }

    // ── masking ────────────────────────────────────────────────────────────────

    @Test
    fun `value column returns masked placeholder when row is not revealed`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("API_KEY", "super-secret")))
        assertThat(model.getValueAt(0, SecretsTableModel.COL_VALUE))
            .isEqualTo(SecretsTableModel.MASKED_PLACEHOLDER)
    }

    @Test
    fun `value column returns raw value when row is revealed`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("API_KEY", "super-secret", revealed = true)))
        assertThat(model.getValueAt(0, SecretsTableModel.COL_VALUE)).isEqualTo("super-secret")
    }

    // ── editing ────────────────────────────────────────────────────────────────

    @Test
    fun `setValueAt on value column marks row modified and updates value`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("KEY", "old")))

        model.setValueAt("new-value", 0, SecretsTableModel.COL_VALUE)

        assertThat(model.rows[0].value).isEqualTo("new-value")
        assertThat(model.rows[0].modified).isTrue()
    }

    @Test
    fun `setValueAt on non-value column is ignored`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("KEY", "v")))

        model.setValueAt("ignored", 0, SecretsTableModel.COL_NAME)

        assertThat(model.rows[0].key).isEqualTo("KEY")
        assertThat(model.rows[0].modified).isFalse()
    }

    @Test
    fun `isCellEditable returns false on a masked value cell`() {
        // Guards against committing the masked placeholder as the secret's new value:
        // a masked cell must be revealed first before the user can edit.
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("KEY", "v", revealed = false)))

        assertThat(model.isCellEditable(0, SecretsTableModel.COL_VALUE)).isFalse()
    }

    @Test
    fun `isCellEditable returns true on a revealed value cell`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("KEY", "v", revealed = true)))

        assertThat(model.isCellEditable(0, SecretsTableModel.COL_NAME)).isFalse()
        assertThat(model.isCellEditable(0, SecretsTableModel.COL_VALUE)).isTrue()
    }

    // ── hasModifiedRows ────────────────────────────────────────────────────────

    @Test
    fun `hasModifiedRows returns false when no rows are modified`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("A", "v1"), SecretRow("B", "v2")))
        assertThat(model.hasModifiedRows()).isFalse()
    }

    @Test
    fun `hasModifiedRows returns true after a setValueAt call`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("A", "v1")))

        model.setValueAt("changed", 0, SecretsTableModel.COL_VALUE)

        assertThat(model.hasModifiedRows()).isTrue()
    }

    // ── setRows ────────────────────────────────────────────────────────────────

    @Test
    fun `setRows replaces all rows and clears modified state`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("OLD", "old-val")))
        model.setValueAt("changed", 0, SecretsTableModel.COL_VALUE)
        assertThat(model.hasModifiedRows()).isTrue()

        model.setRows(listOf(SecretRow("NEW_A", "a"), SecretRow("NEW_B", "b")))

        assertThat(model.rowCount).isEqualTo(2)
        assertThat(model.getValueAt(0, SecretsTableModel.COL_NAME)).isEqualTo("NEW_A")
        assertThat(model.hasModifiedRows()).isFalse()
    }

    @Test
    fun `setRows with empty list results in zero rows`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("K", "v")))
        model.setRows(emptyList())
        assertThat(model.rowCount).isEqualTo(0)
    }

    // ── security: toString never exposes values ────────────────────────────────

    @Test
    fun `model toString never prints secret values`() {
        val model = SecretsTableModel()
        model.setRows(listOf(SecretRow("KEY", "TOP_SECRET_LEAK_CHECK")))
        assertThat(model.toString()).doesNotContain("TOP_SECRET_LEAK_CHECK")
    }

    @Test
    fun `SecretRow toString never prints secret value`() {
        val row = SecretRow("MY_KEY", "SHOULD_NOT_APPEAR_IN_TO_STRING")
        assertThat(row.toString()).doesNotContain("SHOULD_NOT_APPEAR_IN_TO_STRING")
    }
}
