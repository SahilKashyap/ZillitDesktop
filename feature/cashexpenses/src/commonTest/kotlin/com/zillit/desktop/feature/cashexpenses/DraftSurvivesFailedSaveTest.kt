package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A save that fails — offline, rejected, timed out — must leave the typing
 * where the user can retry it. The float request and the coding editor both
 * used to clear themselves before the server answered; these pin the fix and
 * the success path that does clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftSurvivesFailedSaveTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = CashViewer(userId = "u1", departmentIdentifier = "camera", designationIdentifier = null)

    private fun viewModel(repository: CashRepository) =
        CashExpensesViewModel(repository = repository, viewer = { crew }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `a float request that fails to send keeps its amount and purpose`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = false)
        val vm = viewModel(repository)
        val typed = FloatRequestDraft(amount = "250", purpose = "Location petty cash")

        vm.onEvent(CashEvent.EditFloatRequest(typed))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        assertEquals(1, repository.floatRequests, "the request was attempted")
        assertEquals(typed, vm.state.value.floatDraft, "a failed send must not wipe the form")

        repository.writesSucceed = true
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        assertEquals(FloatRequestDraft(), vm.state.value.floatDraft, "a sent request clears the form")
        assertEquals(2, repository.floatRequests)
    }

    @Test
    fun `coding that fails to save stays open for a retry`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = false)
        val vm = viewModel(repository)

        vm.onEvent(CashEvent.Open(CashDestination.CodingQueue))
        advanceUntilIdle()
        vm.onEvent(CashEvent.OpenCoding(batchId = "b1", claimId = "c1"))
        val open = assertNotNull(vm.state.value.coding, "the editor opened on the queued receipt")

        vm.onEvent(CashEvent.SaveCoding)
        advanceUntilIdle()

        assertEquals(1, repository.codingSaves, "the save was attempted")
        assertEquals(open, vm.state.value.coding, "a failed save must not close the editor")

        repository.writesSucceed = true
        vm.onEvent(CashEvent.SaveCoding)
        advanceUntilIdle()

        assertNull(vm.state.value.coding, "a saved coding closes the editor")
        assertEquals(2, repository.codingSaves)
    }

    /**
     * The queue only offers Approve to an approver, and this is the money.
     *
     * `resolveConfirm` dispatched every confirmable action straight to the
     * repository with no rights check of its own, so a prompt reaching it
     * approved a float or a batch outright. The screens gate it
     * (`QueuePage` on `viewer.isApprover`); the handler did not.
     */
    @Test
    fun `a non-approver cannot approve a float`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true)
        val vm = viewModel(repository)

        vm.onEvent(
            CashEvent.Ask(
                CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "Approve this float", ""),
            ),
        )
        vm.onEvent(CashEvent.ConfirmPrompt)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repository.floatApprovals, "a non-approver approved a float")
    }

    /**
     * The float lifecycle is an accountant's, per `FloatPages` — the whole
     * action column renders "—" for anyone else. Closing a float is final.
     */
    @Test
    fun `a non-accountant cannot close a float`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true)
        val vm = viewModel(repository)

        vm.onEvent(
            CashEvent.Ask(
                CashPrompt.Confirm(ConfirmAction.CloseFloat, "f1", "Close this float", ""),
            ),
        )
        vm.onEvent(CashEvent.ConfirmPrompt)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repository.floatCloses, "a non-accountant closed a float")
    }

}
