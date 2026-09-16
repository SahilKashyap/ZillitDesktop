package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.formsignature.data.FORM_SIGN_SYNC_EVENTS
import com.zillit.desktop.feature.formsignature.data.refreshKindsFor
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `document:*` pulse refetches the list it names, only while that list
 * is on screen — the web's per-page refetch handlers (`StandardFormsV2.jsx`,
 * `DocumentsForSignature.jsx`, `FormPage.jsx`) as targeted reloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSignatureSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the event → kind map ---------------------------------------------

    @Test
    fun `events map to the lists their web pages refetch`() {
        assertEquals(
            listOf(FormSignRefresh.Forms),
            refreshKindsFor(SocketEventName("document:added:general")),
        )
        assertEquals(
            listOf(FormSignRefresh.Documents),
            refreshKindsFor(SocketEventName("document:sent:signature")),
        )
        assertEquals(
            listOf(FormSignRefresh.Forms, FormSignRefresh.Documents),
            refreshKindsFor(SocketEventName("document:signed")),
            "both web lists refetch on a signing",
        )
        assertEquals(
            emptyList(),
            refreshKindsFor(SocketEventName("document:message:added")),
            "the chat family is the board engine's, not this listener's",
        )
    }

    /**
     * A form assigned to this user, or added to the library, lands live.
     *
     * These four were excluded as "V1-only" on the web's authority; iOS's
     * Form & Signature 2.0 refreshes the standard-forms list from all of them
     * (`StandardFormsView.swift:40-51`), and the desktop is that tool.
     */
    @Test
    fun `an assigned or library form refetches the forms list`() {
        listOf(
            "document:added:user:form",
            "document:added:user:contract",
            "document:added:saved:form",
            "document:added:saved:contract",
        ).forEach { name ->
            assertEquals(
                listOf(FormSignRefresh.Forms),
                refreshKindsFor(SocketEventName(name)),
                "$name must refresh the standard forms",
            )
            assertTrue(SocketEventName(name) in FORM_SIGN_SYNC_EVENTS, "$name must be subscribed")
        }
    }

    // -- the view model ----------------------------------------------------

    @Test
    fun `a pulse reloads only the list on screen`() = runTest(dispatcher) {
        val events = MutableSharedFlow<FormSignRefresh>()
        val repo = FakeFormSignatureRepository(refreshes = events)
        val model = FormSignatureViewModel(
            repository = repo,
            transfer = NoTransfer,
            pdfWork = FakePdf(),
            resolveViewer = { FormSignatureViewer(canView = true, canPost = true, ready = true) },
            currentUserId = { "u1" },
            newId = { "id" },
        )

        model.start()
        runCurrent()
        assertEquals(0, repo.documentLoads, "the hub loads no lists")

        events.emit(FormSignRefresh.Documents)
        runCurrent()
        assertEquals(0, repo.documentLoads, "an event for a list not on screen is ignored")

        model.onEvent(FormSignatureEvent.Open(FormSignScreen.DocumentsForSignature))
        runCurrent()
        assertEquals(1, repo.documentLoads)

        events.emit(FormSignRefresh.Documents)
        runCurrent()
        assertEquals(2, repo.documentLoads, "the pulse re-runs exactly one load")

        events.emit(FormSignRefresh.Forms)
        runCurrent()
        assertEquals(0, repo.formLoads, "a forms pulse leaves the documents screen alone")
    }
}
