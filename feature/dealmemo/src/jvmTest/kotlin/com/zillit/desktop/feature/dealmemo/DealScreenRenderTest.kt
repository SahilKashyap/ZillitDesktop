package com.zillit.desktop.feature.dealmemo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.dealmemo.domain.Agreement
import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealRates
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DealViewer
import com.zillit.desktop.feature.dealmemo.domain.Union
import com.zillit.desktop.feature.dealmemo.ui.DealDestination
import com.zillit.desktop.feature.dealmemo.ui.DealMemoScreen
import com.zillit.desktop.feature.dealmemo.ui.DealUiState
import kotlin.test.Test

/** Composes the real Deal Memos screen for both audiences. */
@OptIn(ExperimentalTestApi::class)
class DealScreenRenderTest {

    private val controller = DealViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_financial_controller_accounts",
    )

    private val crew = DealViewer("user-2", "department_camera", null)

    private fun deal(
        id: String = "deal-1",
        acknowledgedAt: Long? = AGREED_AT,
        amendedAt: Long? = null,
    ) = Deal(
        id = id,
        userId = "user-2",
        crewName = "Ada Lovelace",
        email = "ada@example.com",
        departmentId = "dept-1",
        departmentName = "Camera",
        designation = "Gaffer",
        status = DealStatus.Active,
        currency = "GBP",
        rates = DealRates(
            weeklyRate = 2_400.0,
            dailyRate = 400.0,
            overtimeRate = 60.0,
            standardHours = 11.0,
            daysPerWeek = 6.0,
            boxRental = 150.0,
        ),
        startDate = 1_754_000_000_000,
        endDate = null,
        unionName = "BECTU",
        agreementName = "PACT/BECTU Feature Film",
        nominalCode = "7000",
        notes = "Six-day week, seventh at overtime.",
        amendedAt = amendedAt,
        acknowledgedAt = acknowledgedAt,
        createdAt = 1_754_000_000_000,
    )

    private fun state(destination: DealDestination, viewer: DealViewer = controller) = DealUiState(
        viewer = viewer,
        destination = destination,
        deals = listOf(deal(), deal("deal-2")),
        myDeal = deal(),
        unions = listOf(Union("u-1", "BECTU", 3)),
        agreements = listOf(Agreement("a-1", "PACT/BECTU Feature Film", "u-1", null)),
        selectedId = "deal-1",
    )

    @Test
    fun `every destination composes for both audiences and both themes`() {
        listOf(controller to false, controller to true, crew to false).forEach { (viewer, dark) ->
            DealDestination.entries.filter { it.visibleTo(viewer) }.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            DealMemoScreen(state = state(destination, viewer), onEvent = {})
                        }
                    }
                    onNodeWithText("Deal Memos").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `a crew member with no deal is told so plainly`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DealMemoScreen(
                        state = state(DealDestination.MyDeal, crew).copy(myDeal = null),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No deal on file yet").assertIsDisplayed()
        }
    }

    @Test
    fun `an amendment after agreement asks the crew member to read it again`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DealMemoScreen(
                        state = state(DealDestination.MyDeal, crew).copy(
                            myDeal = deal(acknowledgedAt = 1_000, amendedAt = 2_000),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("These terms were amended after you agreed to them. Read them again and confirm.")
                .assertIsDisplayed()
            onNodeWithText("I agree to these terms").assertIsDisplayed()
        }
    }

    @Test
    fun `an agreed deal says when it was agreed rather than asking again`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DealMemoScreen(state = state(DealDestination.MyDeal, crew), onEvent = {})
                }
            }
            // The date is rendered in the machine's own zone (EpochDate, by design),
            // so a literal here passes only east of UTC+1:47. Ask the same reader
            // the screen asks: what matters is that the agreed date is shown at
            // all, not which timezone the test host keeps.
            onNodeWithText("You agreed to these terms on ${EpochDate.date(AGREED_AT)}.")
                .assertIsDisplayed()
        }
    }
}

/** The acknowledgement stamp the fixture agrees on. */
private const val AGREED_AT = 1_754_000_000_000
