package com.zillit.desktop.feature.saportal

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.saportal.domain.AccountCheck
import com.zillit.desktop.feature.saportal.domain.HolidayPot
import com.zillit.desktop.feature.saportal.domain.PayRun
import com.zillit.desktop.feature.saportal.domain.PayStatement
import com.zillit.desktop.feature.saportal.domain.SaProfile
import com.zillit.desktop.feature.saportal.domain.SaSummary
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import com.zillit.desktop.feature.saportal.domain.VoucherTally
import com.zillit.desktop.feature.saportal.ui.SaDestination
import com.zillit.desktop.feature.saportal.ui.SaPortalScreen
import com.zillit.desktop.feature.saportal.ui.SaUiState
import kotlin.test.Test

/** Composes the real portal in each state, light and dark. */
@OptIn(ExperimentalTestApi::class)
class SaPortalRenderTest {

    private val artiste = SaViewer(userId = "u1", displayName = "Ada Lovelace", ready = true)

    private fun voucher(
        id: String = "v1",
        dayStatus: String = "submitted",
        status: VoucherStatus = VoucherStatus.Pending,
    ) = Voucher(
        id = id,
        code = "VCH-0042-20260826",
        shootDate = 1_772_755_200_000,
        callTime = "07:00",
        wrapTime = "19:30",
        minutesWorked = 690,
        gross = 184.5,
        currency = "GBP",
        status = status,
        dayStatus = dayStatus,
        role = "Pub regular",
    )

    private fun state(destination: SaDestination = SaDestination.Dashboard) = SaUiState(
        viewer = artiste,
        destination = destination,
        summary = SaSummary(
            vouchers = VoucherTally(total = 9, pending = 2, signed = 4, paid = 3),
            ytdGross = 1_640.5,
            totalGross = 4_820.0,
            holidayAccrued = 198.4,
            nextBooking = voucher(id = "v2", dayStatus = "draft"),
        ),
        vouchers = listOf(voucher()),
        profile = SaProfile(id = "a1", currency = "GBP", artisteRef = "SA-0042"),
    )

    @Test
    fun `every section composes in both themes`() {
        listOf(false, true).forEach { dark ->
            SaDestination.entries.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            SaPortalScreen(state = state(destination), onEvent = {})
                        }
                    }
                    onNodeWithText("My work").assertExists()
                }
            }
        }
    }

    /**
     * The whole point of the overview: an unsigned day is money not yet on
     * its way, so it leads rather than sitting under a year-to-date figure.
     */
    @Test
    fun `a day awaiting signature is called out first`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = state(), onEvent = {}) }
            }
            onNodeWithText("Waiting for your signature").assertExists()
            onNodeWithText("A day is not sent for payment until you have signed it.")
                .assertExists()
        }
    }

    @Test
    fun `with nothing to sign the call-out is absent`() {
        val settled = state().copy(vouchers = listOf(voucher(dayStatus = "signed")))

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = settled, onEvent = {}) }
            }
            onAllNodesWithText("Waiting for your signature").assertCountEquals(0)
        }
    }

    /** Only a submitted day may be signed, so the control is absent otherwise. */
    @Test
    fun `the sign control follows the day, not the pill`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    SaPortalScreen(state = state(SaDestination.Vouchers), onEvent = {})
                }
            }
            onAllNodesWithText("Sign").assertCountEquals(1)
        }

        val locked = state(SaDestination.Vouchers)
            .copy(vouchers = listOf(voucher(dayStatus = "draft")))
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = locked, onEvent = {}) }
            }
            onAllNodesWithText("Sign").assertCountEquals(0)
        }
    }

    /**
     * Most crew are not artistes. They are told so plainly rather than shown
     * a failure they cannot act on.
     */
    @Test
    fun `someone who is not an artiste is told, not shown an error`() {
        val notMine = SaUiState(viewer = artiste, notAnArtiste = true)

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = notMine, onEvent = {}) }
            }
            onNodeWithText("You are not booked as an artiste here").assertExists()
        }
    }

    @Test
    fun `a viewer without the tool is refused`() {
        val blocked = SaUiState(viewer = SaViewer(userId = "u1", canView = false, ready = true))

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = blocked, onEvent = {}) }
            }
            onNodeWithText("No access").assertExists()
        }
    }

    @Test
    fun `the pay page shows the holiday pot and the weeks`() {
        val paid = state(SaDestination.Pay).copy(
            pay = PayStatement(
                runs = listOf(PayRun(weekStarting = 1_772_668_800_000, gross = 610.5, days = 4)),
                holiday = HolidayPot(total = 240.0, paid = 41.6, accrued = 198.4),
            ),
        )

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = paid, onEvent = {}) }
            }
            // ZillitStatTile upper-cases its label.
            onNodeWithText("ACCRUED").assertExists()
            onNodeWithText("4 days").assertExists()
        }
    }

    /** An outstanding document is why a payment stalls, so it leads the profile. */
    @Test
    fun `the profile leads with what is still needed`() {
        val incomplete = state(SaDestination.Profile).copy(
            profile = SaProfile(
                id = "a1",
                artisteRef = "SA-0042",
                accountStatus = listOf(
                    AccountCheck(key = "bank", label = "Bank details", ok = true),
                    AccountCheck(key = "id", label = "Photo ID", ok = false, detail = "Not uploaded"),
                ),
            ),
        )

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { SaPortalScreen(state = incomplete, onEvent = {}) }
            }
            onNodeWithText("Still needed").assertExists()
            onNodeWithText("Payments cannot be made up until these are in place.")
                .assertExists()
            onNodeWithText("Photo ID").assertExists()
        }
    }
}
