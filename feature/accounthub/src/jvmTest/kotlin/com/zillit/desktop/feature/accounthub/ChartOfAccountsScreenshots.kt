package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.LayerDelete
import com.zillit.desktop.feature.accounthub.ui.LayerInUse
import com.zillit.desktop.feature.accounthub.ui.LayerNodeDraft
import com.zillit.desktop.feature.accounthub.ui.LayerSetDraft
import com.zillit.desktop.feature.accounthub.ui.TreeFold
import com.zillit.desktop.feature.accounthub.ui.pages.ChartOfAccountsPage
import java.io.File
import kotlin.test.Test

/**
 * Every Chart of Accounts surface as a PNG, for looking at — not asserting on.
 *
 * Opt-in: runs only when `COA_SHOTS` names a directory, so the normal test pass
 * stays fast and writes nothing. Sized to the pane the Account Hub gives a page.
 */
class ChartOfAccountsScreenshots {

    @Test
    fun `render every surface`() {
        val dir = System.getenv("COA_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val base = ChartState(accounts = ChartFixtures.accounts, loaded = true)
        val shots = listOf(
            "tree" to base,
            "tree-open" to base.copy(fold = TreeFold.AllOpen),
            "tree-search" to base.copy(search = "writ"),
            "table" to base.copy(mode = ChartMode.Table),
            "balance" to base.copy(view = ChartView.BalanceSheet, fold = TreeFold.AllOpen),
            "balance-empty" to base.copy(
                view = ChartView.BalanceSheet,
                accounts = ChartFixtures.accounts.filter { it.costType == CoaCostType.Expense },
            ),
            "bulk" to ChartFixtures.bulk(),
            "edit" to ChartFixtures.editing(ChartFixtures.story).let { chart ->
                chart.copy(form = chart.form?.copy(lineType = CoaLineType.Category, parentId = null))
            },
            "edit-budget" to ChartFixtures.editing(ChartFixtures.cameraHire),
            "deactivate" to base.copy(confirmDeactivate = ChartFixtures.writing),
            "layers" to base.copy(view = ChartView.Layers, trackingSets = ChartFixtures.layers, openLayer = "t1"),
            "layers-empty" to base.copy(view = ChartView.Layers),
            "layer-set" to base.copy(
                view = ChartView.Layers,
                trackingSets = ChartFixtures.layers,
                layerSetDraft = LayerSetDraft(ChartFixtures.layers.first(), isNew = false),
            ),
            "layer-code" to base.copy(
                view = ChartView.Layers,
                trackingSets = ChartFixtures.layers,
                layerNodeDraft = LayerNodeDraft(ChartFixtures.layers.first().nodes.first(), isNew = true),
            ),
            "layer-delete" to base.copy(
                view = ChartView.Layers,
                trackingSets = ChartFixtures.layers,
                layerDelete = LayerDelete.WholeSet(ChartFixtures.layers.first()),
            ),
            "layer-in-use" to base.copy(
                view = ChartView.Layers,
                trackingSets = ChartFixtures.layers,
                layerInUse = LayerInUse(
                    "Can't delete this code",
                    "Cannot delete LOC-LON — its codes are referenced in: Purchase Orders, Invoices",
                ),
            ),
        )
        listOf(false, true).forEach { dark ->
            shots.forEach { (name, chart) ->
                shoot(File(dir, "$name-${if (dark) "dark" else "light"}.png"), ChartFixtures.state(chart), dark)
            }
        }
    }

    private fun shoot(file: File, state: AccountHubUiState, dark: Boolean) {
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = HEIGHT_DP * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                ChartOfAccountsPage(state, {}, canImportBudget = true)
            }
        }
        // A late frame: dialogs fade in, and frame 0 catches them mid-way.
        scene.render(0L)
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2
        const val WIDTH_DP = 1180
        const val HEIGHT_DP = 900
        const val SETTLED_NANOS = 2_000_000_000L
    }
}
