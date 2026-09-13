package com.zillit.desktop.feature.dealmemo

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.dealmemo.ui.DealMemoScreen
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import java.io.File
import kotlin.test.Test

/**
 * Every page and dialog as a PNG, for looking at — not asserting on.
 *
 * Opt-in: runs only when `DEALMEMO_SHOTS` names a directory, so the normal test
 * pass stays fast and writes nothing. Sized to the maximised tool window.
 */
class DealMemoScreenshots {

    @Test
    fun `render every page`() {
        val dir = System.getenv("DEALMEMO_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val pages = listOf(
            "01-all-deals" to DealMemoFixtures.allDeals(),
            "02-setup-gate" to DealMemoFixtures.setupGate(),
            "03-delete-confirm" to DealMemoFixtures.deleteConfirm(),
            "04-history" to DealMemoFixtures.history(),
            "05-overview" to DealMemoFixtures.overview(),
            "06-queue" to DealMemoFixtures.queue(),
            "07-queue-empty" to DealMemoFixtures.emptyQueue(),
            "08-my-deal-empty" to DealMemoFixtures.myDealEmpty(),
            "09-notices" to DealMemoFixtures.notices(),
            "10-send-notice" to DealMemoFixtures.sendNotice(),
            "11-notice-template" to DealMemoFixtures.noticeTemplate(),
            "12-rates-skeleton" to DealMemoFixtures.ratesSkeleton(),
            "13-rates-welcome" to DealMemoFixtures.ratesWelcome(),
            "14-rates-territory" to DealMemoFixtures.ratesTerritory(),
            "15-rates-branch" to DealMemoFixtures.ratesBranch(),
            "16-rates-agreement" to DealMemoFixtures.ratesAgreement(),
            "17-deal-page" to DealPageFixtures.dealPage(),
            "18-my-deal-embedded" to DealPageFixtures.myDealEmbedded(),
            "19-crew-details" to DealPageFixtures.crewForm(step = 0),
            "20-crew-emergency-errors" to DealPageFixtures.crewErrors(),
            "21-crew-representative" to DealPageFixtures.crewForm(step = 2),
            "22-crew-bank" to DealPageFixtures.crewForm(step = 3),
            "23-crew-loanout" to DealPageFixtures.crewLoanOut(),
            "24-crew-discard" to DealPageFixtures.crewDiscard(),
            "25-not-ready" to DealPageFixtures.notReady(),
            "26-nominals" to DealPageFixtures.nominals(),
            "27-rules-grid" to DealPageFixtures.rulesGrid(),
            "30-builder-deal" to BuilderFixtures.dealPage(),
            "31-builder-validation" to BuilderFixtures.validation(),
            "32-builder-issue-preview" to BuilderFixtures.issuePreview(),
            "33-builder-picture" to BuilderFixtures.picture(),
            "34-builder-buyout" to BuilderFixtures.buyout(),
            "35-builder-deal-rules" to BuilderFixtures.rulesGrid(),
            "40-setup-union" to BuilderFixtures.unionSetup(),
            "41-setup-nonunion" to BuilderFixtures.nonUnionSetup(),
            "42-setup-company" to BuilderFixtures.companyModal(),
            "43-setup-rule-import" to BuilderFixtures.ruleImport(),
            "44-hub-cards" to BuilderFixtures.hubCards(),
            "45-hub-delete" to BuilderFixtures.hubDelete(),
            "46-hub-inline" to BuilderFixtures.hubInline(),
        ) + BuilderFixtures.dealEditors.map { (id, name) ->
            val slug = name.lowercase().replace(Regex("[^a-z]+"), "-")
            "3${id.toString().padStart(2, '0')}-editor-$slug" to BuilderFixtures.dealEditor(id)
        }
        val only = System.getenv("DEALMEMO_ONLY")?.takeIf { it.isNotBlank() }
        listOf(false, true).forEach { dark ->
            if (dark && System.getenv("DEALMEMO_LIGHT_ONLY") != null) return@forEach
            pages.filter { only == null || it.first.contains(only) }.forEach { (name, state) ->
                shoot(File(dir, "$name-${if (dark) "dark" else "light"}.png"), state, dark)
            }
        }
    }

    private fun shoot(file: File, state: DealMemoUiState, dark: Boolean) {
        // A taller window shows a scrolling page's lower half: DEALMEMO_HEIGHT=1800.
        val height = System.getenv("DEALMEMO_HEIGHT")?.toIntOrNull() ?: HEIGHT_DP
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = height * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                DealMemoScreen(state = state, onEvent = {})
            }
        }
        scene.render(0L)
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2
        const val WIDTH_DP = 1440
        const val HEIGHT_DP = 900
        const val SETTLED_NANOS = 2_000_000_000L
    }
}
