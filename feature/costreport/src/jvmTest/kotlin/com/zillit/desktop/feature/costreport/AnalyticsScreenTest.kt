package com.zillit.desktop.feature.costreport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costreport.data.parseAnalyticsModules
import com.zillit.desktop.feature.costreport.data.parseAnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsQuery
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsEvent
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsScreen
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsStatus
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsUiState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Analytics page composed with every block type, in both themes, and the
 * clicks that leave it: a module card, an alert, a tab, Filters and Back.
 */
@OptIn(ExperimentalTestApi::class)
class AnalyticsScreenTest {

    private val modules = parseAnalyticsModules(
        Json.parseToJsonElement(
            """[{"id":"payroll","label":"Payroll","tone":"amber","total":1905000,"delta":4.2,"spark":[1,2,3]},
               {"id":"po","label":"Purchase Orders","tone":"blue","total":842500}]""",
        ),
    )

    private val page = parseAnalyticsPage(
        Json.parseToJsonElement(
            """{"currency":"GBP","blocks":[
              {"type":"kpi-row","items":[{"label":"EFC","value":4390000,"spark":[1,2,3]},{"label":"POs","value":7,"fmt":"ratio","ratioOf":9}]},
              {"type":"row","cols":"1.6fr 1fr","align":"stretch","blocks":[
                {"type":"forecast","data":{"cum":[1,2,3],"proj":[3,4,5],"budget":4.5,"total":6}},
                {"type":"donut","title":"Groups","segments":[{"label":"Crew","value":3,"color":"#e8861a"}],"centerValue":3}]},
              {"type":"row","cols":"repeat(auto-fit, minmax(220px, 1fr))","blocks":[
                {"type":"bars","title":"Bars","labels":["W1","W2"],"series":[1,2]},
                {"type":"stacked-bars","title":"Stacks","labels":["W1"],"stacks":[{"segments":[{"label":"A","value":1,"color":"#14a394"}]}]},
                {"type":"trend","title":"Trend","labels":["W1","W2"],"series":[{"label":"A","data":[1,null],"fill":true}],"budget":2},
                {"type":"hbars","title":"Ranked","rows":[{"label":"Camera","value":2}]}]},
              {"type":"table","title":"Rows","columns":[{"key":"n","label":"Name","cell":"avatar"},{"key":"v","label":"Spend","cell":"progress"},
                {"key":"s","label":"Status","cell":"status-pill"},{"key":"m","label":"Method","cell":"method-chip"},{"key":"c","label":"Card","cell":"card-ref"},
                {"key":"p","label":"Share","cell":"percent"},{"key":"l","label":"Link","cell":"link"}],
               "rows":[{"n":"Ada Lovelace","v":10,"pct":130,"s":"Paid","m":"BACS","c":"4421","holder":"Ada","p":12.5,"l":"Open","href":"/film-tools/payroll"}]},
              {"type":"method-cards","methods":[{"label":"BACS","value":1,"count":2}]},
              {"type":"gauge-cards","cards":[{"label":"BACS","value":1,"count":2,"turn":"2 days"}]},
              {"type":"alerts","alerts":[{"title":"Camera overtime is over allowance","module":"payroll","tone":"red"}]},
              {"type":"module-forecast","rows":[{"module":"payroll","actual":1,"committed":1,"etc":1,"budget":2}]},
              {"type":"snapshot-cards","modules":["payroll","po","missing"]},
              {"type":"a-block-from-the-future"}
            ],"tabs":[{"id":"ots","label":"OTs","blocks":[]},{"id":"pen","label":"Penalties"}]}""",
        ),
    )

    private val state = AnalyticsUiState(
        selected = "payroll",
        modules = modules,
        status = AnalyticsStatus.Ready,
        page = page,
        activeTab = "ots",
        applied = AnalyticsQuery(currency = "GBP"),
    )

    @Test
    fun everyBlockTypeComposesInBothThemesAndStates() {
        val states = listOf(
            state,
            state.copy(filtersOpen = true),
            state.copy(status = AnalyticsStatus.Loading, page = null),
            state.copy(status = AnalyticsStatus.Error, error = "Service unavailable", page = null),
            state.copy(status = AnalyticsStatus.Empty, page = null),
        )
        states.forEach { shown ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent { ZillitTheme(darkTheme = dark) { AnalyticsScreen(state = shown, onEvent = {}) } }
                    waitForIdle()
                }
            }
        }
    }

    @Test
    fun clicksBecomeEvents() = runComposeUiTest {
        val events = mutableListOf<AnalyticsEvent>()
        setContent { ZillitTheme { AnalyticsScreen(state = state, onEvent = { events += it }) } }

        // The strip card; the module-card grid further down names it too.
        onAllNodesWithText("Purchase Orders")[0].performClick()
        // Both sit below the fold: scrolled to first, as a user would.
        onNodeWithText("Camera overtime is over allowance").performScrollTo().performClick()
        onNodeWithText("Penalties").performScrollTo().performClick()
        onNodeWithText("Filters").performClick()
        onNodeWithText("Overview").performScrollTo().performClick()

        assertTrue(AnalyticsEvent.Select("po") in events, "module card: $events")
        assertTrue(AnalyticsEvent.Select("payroll") in events, "alert: $events")
        assertTrue(AnalyticsEvent.SelectTab("pen") in events, "tab: $events")
        assertTrue(AnalyticsEvent.ToggleFilters in events, "filters: $events")
        assertTrue(AnalyticsEvent.Select(AnalyticsUiState.OVERVIEW) in events, "overview: $events")
    }
}
