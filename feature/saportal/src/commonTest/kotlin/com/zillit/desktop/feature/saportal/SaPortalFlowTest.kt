package com.zillit.desktop.feature.saportal

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.saportal.domain.ArtisteQuery
import com.zillit.desktop.feature.saportal.domain.PayStatement
import com.zillit.desktop.feature.saportal.domain.SaPortalRepository
import com.zillit.desktop.feature.saportal.domain.SaProfile
import com.zillit.desktop.feature.saportal.domain.SaSummary
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherDetail
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import com.zillit.desktop.feature.saportal.ui.SaDestination
import com.zillit.desktop.feature.saportal.ui.SaEvent
import com.zillit.desktop.feature.saportal.ui.SaPortalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The portal's own behaviour: what it loads, what it refuses to send, and
 * how it treats a user who is not an artiste on this production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaPortalFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun voucher(
        id: String = "v1",
        dayStatus: String = "submitted",
        status: VoucherStatus = VoucherStatus.Pending,
    ) = Voucher(id = id, code = "VCH-$id", dayStatus = dayStatus, status = status, gross = 100.0)

    private class FakeRepo : SaPortalRepository {
        var summaryResult: ZillitResult<SaSummary> = ZillitResult.Success(SaSummary())
        var voucherRows: List<Voucher> = emptyList()
        var signResult: ZillitResult<Unit> = ZillitResult.Success(Unit)
        val signed = mutableListOf<Triple<String, String, Pair<Boolean, Boolean>>>()
        val raised = mutableListOf<Pair<String, String>>()
        val replies = mutableListOf<Pair<String, String>>()
        var voucherLoads = 0
        var queryRows: List<ArtisteQuery> = emptyList()

        override suspend fun summary() = summaryResult
        override suspend fun profile(): ZillitResult<SaProfile> = ZillitResult.Success(SaProfile(id = "a1"))
        override suspend fun vouchers(status: VoucherStatus?): ZillitResult<List<Voucher>> {
            voucherLoads++
            return ZillitResult.Success(
                if (status == null) voucherRows else voucherRows.filter { it.status == status },
            )
        }
        override suspend fun voucher(id: String): ZillitResult<VoucherDetail> =
            voucherRows.firstOrNull { it.id == id }
                ?.let { ZillitResult.Success(VoucherDetail(voucher = it)) }
                ?: ZillitResult.Failure(ZillitError.Unknown("no such voucher"))
        override suspend fun sign(
            id: String,
            typedName: String,
            consentAccuracy: Boolean,
            consentESign: Boolean,
        ): ZillitResult<Unit> {
            if (signResult is ZillitResult.Success) {
                signed += Triple(id, typedName, consentAccuracy to consentESign)
            }
            return signResult
        }
        override suspend fun pay(): ZillitResult<PayStatement> = ZillitResult.Success(PayStatement())
        override suspend fun queries(): ZillitResult<List<ArtisteQuery>> = ZillitResult.Success(queryRows)
        override suspend fun query(id: String): ZillitResult<ArtisteQuery> =
            queryRows.firstOrNull { it.id == id }
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("no such query"))
        override suspend fun raiseQuery(voucherId: String, text: String): ZillitResult<Unit> {
            raised += voucherId to text
            return ZillitResult.Success(Unit)
        }
        override suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit> {
            replies += id to text
            return ZillitResult.Success(Unit)
        }
        override suspend fun resolveQuery(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    }

    private fun model(repo: FakeRepo) = SaPortalViewModel(
        repository = repo,
        viewer = { SaViewer(userId = "u1", displayName = "Ada", ready = true) },
    ).also { it.start() }

    // -- what an artiste opens it for -----------------------------------------

    @Test
    fun `opening loads the summary and the days`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.voucherRows = listOf(voucher())
        val vm = model(repo)
        runCurrent()

        assertEquals(1, repo.voucherLoads)
        assertEquals(1, vm.state.value.vouchers.size)
    }

    @Test
    fun `the days still to sign are the submitted ones`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.voucherRows = listOf(
            voucher(id = "v1", dayStatus = "submitted"),
            voucher(id = "v2", dayStatus = "draft"),
            voucher(id = "v3", dayStatus = "signed", status = VoucherStatus.Signed),
        )
        val vm = model(repo)
        runCurrent()

        assertEquals(listOf("v1"), vm.state.value.awaitingSignature.map { it.id })
        assertTrue(vm.state.value.hasOutstanding)
    }

    // -- signing ---------------------------------------------------------------

    /**
     * A signature is a legal act. Both consents and a typed name are what the
     * server records as having been given, so anything less is refused here
     * rather than sent and bounced.
     */
    @Test
    fun `signing without both consents sends nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.voucherRows = listOf(voucher())
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartSigning(voucher()))
        vm.onEvent(SaEvent.SignName("Ada Lovelace"))
        vm.onEvent(SaEvent.SignAccuracy(true))
        vm.onEvent(SaEvent.ConfirmSign)
        runCurrent()

        assertTrue(repo.signed.isEmpty(), "the e-sign consent was never given")
    }

    @Test
    fun `signing without a name sends nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartSigning(voucher()))
        vm.onEvent(SaEvent.SignAccuracy(true))
        vm.onEvent(SaEvent.SignESign(true))
        vm.onEvent(SaEvent.ConfirmSign)
        runCurrent()

        assertTrue(repo.signed.isEmpty())
    }

    @Test
    fun `a complete signature is sent with both consents`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.voucherRows = listOf(voucher())
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartSigning(voucher()))
        vm.onEvent(SaEvent.SignName("  Ada Lovelace  "))
        vm.onEvent(SaEvent.SignAccuracy(true))
        vm.onEvent(SaEvent.SignESign(true))
        vm.onEvent(SaEvent.ConfirmSign)
        runCurrent()

        assertEquals(1, repo.signed.size)
        val (id, name, consents) = repo.signed.single()
        assertEquals("v1", id)
        assertEquals("  Ada Lovelace  ", name, "trimming is the repository's job, not the gate's")
        assertEquals(true to true, consents)
        assertNull(vm.state.value.sign, "the dialog closes")
        assertEquals("Signed VCH-v1", vm.state.value.notice)
    }

    @Test
    fun `a refused signature leaves the dialog open to try again`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.signResult = ZillitResult.Failure(ZillitError.Unknown("nope"))
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartSigning(voucher()))
        vm.onEvent(SaEvent.SignName("Ada"))
        vm.onEvent(SaEvent.SignAccuracy(true))
        vm.onEvent(SaEvent.SignESign(true))
        vm.onEvent(SaEvent.ConfirmSign)
        runCurrent()

        assertEquals("Ada", vm.state.value.sign?.typedName, "the typing survives")
        assertEquals(false, vm.state.value.sign?.saving, "the button is live again")
    }

    // -- not an artiste --------------------------------------------------------

    /**
     * Most crew are not supporting artistes. The server says so with
     * `artiste_not_found_for_user`, and showing that as a red failure would
     * have every grip believing the tool was broken.
     */
    @Test
    fun `a user with no artiste record is told so, not shown an error`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.summaryResult = ZillitResult.Failure(
            ZillitError.Http(status = 404, serverMessage = "artiste_not_found_for_user"),
        )
        val vm = model(repo)
        runCurrent()

        assertTrue(vm.state.value.notAnArtiste)
        assertNull(vm.state.value.error, "not a failure the reader can act on")
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a real failure is still an error`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.summaryResult = ZillitResult.Failure(
            ZillitError.Http(status = 500, serverMessage = "internal_error"),
        )
        val vm = model(repo)
        runCurrent()

        assertFalse(vm.state.value.notAnArtiste)
        assertTrue(vm.state.value.error != null)
    }

    // -- queries ---------------------------------------------------------------

    @Test
    fun `a query names the day it is about`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartQuery(voucher(id = "v7")))
        vm.onEvent(SaEvent.QueryText("Wrap was 20:30"))
        vm.onEvent(SaEvent.SubmitQuery)
        runCurrent()

        assertEquals(listOf("v7" to "Wrap was 20:30"), repo.raised)
        assertNull(vm.state.value.queryDraft)
    }

    @Test
    fun `an empty query is not sent`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(SaEvent.StartQuery(voucher()))
        vm.onEvent(SaEvent.QueryText("   "))
        vm.onEvent(SaEvent.SubmitQuery)
        runCurrent()

        assertTrue(repo.raised.isEmpty())
    }

    @Test
    fun `open threads sort before resolved ones`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.queryRows = listOf(
            ArtisteQuery(id = "q1", status = "resolved", updated = 3_000),
            ArtisteQuery(id = "q2", status = "open", updated = 1_000),
            ArtisteQuery(id = "q3", status = "open", updated = 2_000),
        )
        val vm = model(repo)
        runCurrent()
        vm.onEvent(SaEvent.Open(SaDestination.Queries))
        runCurrent()

        // Newest open first, then the resolved — a resolved thread is done
        // however recently it was touched.
        assertEquals(listOf("q3", "q2", "q1"), vm.state.value.openQueries.map { it.id })
    }

    @Test
    fun `a reply that fails puts the typing back`() = runTest(dispatcher) {
        val repo = object : SaPortalRepository by FakeRepo() {
            override suspend fun query(id: String) =
                ZillitResult.Success(ArtisteQuery(id = "q1", status = "open"))
            override suspend fun replyToQuery(id: String, text: String) =
                ZillitResult.Failure(ZillitError.Unknown("nope"))
        }
        val vm = SaPortalViewModel(repo) { SaViewer(userId = "u1", ready = true) }.also { it.start() }
        runCurrent()

        vm.onEvent(SaEvent.OpenQuery("q1"))
        runCurrent()
        vm.onEvent(SaEvent.ReplyDraft("Any news?"))
        vm.onEvent(SaEvent.SendReply)
        runCurrent()

        assertEquals("Any news?", vm.state.value.replyDraft)
    }

    // -- access ----------------------------------------------------------------

    @Test
    fun `a blocked viewer loads nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = SaPortalViewModel(repo) {
            SaViewer(userId = "u1", canView = false, ready = true)
        }.also { it.start() }
        runCurrent()

        assertEquals(0, repo.voucherLoads)
    }

    @Test
    fun `an unresolved viewer is not blocked`() {
        assertFalse(SaViewer(userId = "u1", canView = false, ready = false).isBlocked)
        assertTrue(SaViewer(userId = "u1", canView = false, ready = true).isBlocked)
    }
}
