package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.feature.formsignature.data.SignDocumentDto
import com.zillit.desktop.feature.formsignature.domain.ChatUnit
import com.zillit.desktop.feature.formsignature.domain.FormBadgeLeaf
import com.zillit.desktop.feature.formsignature.domain.FormSignatureBadges
import com.zillit.desktop.feature.formsignature.domain.FormSignatureUnread
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents are read one at a time, when opened, as iOS reads them
 * (`FormDocumentView.swift:401-446`) — never a whole tab on show — and every
 * leaf a tab counts is still cleared by some open in that tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSignatureBadgeReadsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class RecordingBadges : FormSignatureBadges {
        val live = MutableStateFlow<List<FormBadgeLeaf>>(emptyList())
        override val leaves: Flow<List<FormBadgeLeaf>> get() = live
        val leafReads = mutableListOf<List<FormBadgeLeaf>>()
        val formReads = mutableListOf<String>()
        val chatReads = mutableListOf<String>()

        override fun readStandardForm(formId: String) {
            formReads += formId
        }

        override fun readLeaves(leaves: List<FormBadgeLeaf>) {
            leafReads += leaves
        }

        override fun readChat(unitId: String) {
            chatReads += unitId
        }
    }

    private fun leaf(unit: String, level1: String, level2: String, unread: Int = 1) =
        FormBadgeLeaf(unit, level1, level2, unread)

    private val forSignature = FormSignatureBadges.UNIT_FOR_SIGNATURE
    private val received = FormSignatureBadges.LEVEL_RECEIVED

    private val ledger = listOf(
        leaf(forSignature, received, "d1"),
        leaf(forSignature, received, "d2"),
        leaf(forSignature, received, ""),
        leaf(forSignature, received, "gone"),
        leaf(FormSignatureBadges.UNIT_RECEIVED, "contract", "d1"),
        leaf(forSignature, FormSignatureBadges.LEVEL_FULLY_SIGNED, "d1"),
        leaf(FormSignatureBadges.UNIT_UNDER_DISCUSSION, "form", "d3"),
        leaf(forSignature, "", "d3"),
    )

    private fun doc(id: String, documentId: String = "") =
        SignDocument(id = id, document = StoredDocument(media = "k/$id.pdf", name = "$id.pdf"), documentId = documentId)

    private fun TestScope.model(badges: RecordingBadges, setup: FakeFormSignatureRepository.() -> Unit = {}) =
        FormSignatureViewModel(
            repository = FakeFormSignatureRepository().apply(setup),
            transfer = FakeTransfer,
            pdfWork = FakePdf(),
            resolveViewer = { FormSignatureViewer(canView = true, canPost = true, ready = true) },
            currentUserId = { "me" },
            newId = { "id" },
            badges = badges,
        ).also {
            it.start()
            runCurrent()
        }

    @Test
    fun `a document reads its own leaves in its tab, and the tab's leaves no listed document names`() {
        val unread = FormSignatureUnread(ledger)
        assertEquals(
            listOf(
                leaf(forSignature, received, "d1"),
                leaf(forSignature, received, ""),
                leaf(forSignature, received, "gone"),
                leaf(FormSignatureBadges.UNIT_RECEIVED, "contract", "d1"),
            ),
            unread.documentLeaves(SignDocumentTab.Received, ids = listOf("d1"), listed = listOf("d1", "d2")),
            "never d2's, never Fully signed's",
        )
        assertEquals(
            listOf(leaf(FormSignatureBadges.UNIT_UNDER_DISCUSSION, "form", "d3"), leaf(forSignature, "", "d3")),
            unread.documentLeaves(SignDocumentTab.Uploaded, ids = listOf("x", "d3"), listed = listOf("x", "d3")),
            "either id form names the document",
        )
    }

    @Test
    fun `every leaf a tab counts is cleared by some open in that tab`() {
        val unread = FormSignatureUnread(ledger)
        val listed = listOf("d1", "d2", "d3")
        SignDocumentTab.entries.forEach { tab ->
            val cleared = listed.flatMap { unread.documentLeaves(tab, listOf(it), listed) }.toSet()
            assertEquals(unread.tabLeaves(tab).toSet(), cleared, "$tab")
        }
    }

    @Test
    fun `the All tab's leaves no listed form names ride with any form opened there`() {
        val general = FormSignatureBadges.UNIT_GENERAL
        val allForms = FormSignatureBadges.LEVEL_ALL_FORMS
        val unread = FormSignatureUnread(
            listOf(leaf(general, allForms, "f1"), leaf(general, allForms, "old"), leaf(general, "room", "")),
        )
        assertEquals(listOf(leaf(general, allForms, "old")), unread.unlistedAllForms(listOf("f1", "f2")))
    }

    @Test
    fun `showing the documents or switching a tab reads nothing, opening a document reads it`() =
        runTest(dispatcher) {
            val badges = RecordingBadges().apply { live.value = ledger }
            val vm = model(badges) { documents = listOf(doc("d1"), doc("d2")) }
            vm.onEvent(FormSignatureEvent.Open(FormSignScreen.DocumentsForSignature))
            vm.onEvent(FormSignatureEvent.SwitchDocumentsTab(SignDocumentTab.Received))
            runCurrent()
            assertTrue(badges.leafReads.isEmpty(), "no whole-tab read on show")

            vm.onEvent(FormSignatureEvent.OpenDocument(doc("d2")))
            runCurrent()
            assertEquals(
                setOf(
                    leaf(forSignature, received, "d2"),
                    leaf(forSignature, received, ""),
                    leaf(forSignature, received, "gone"),
                ),
                badges.leafReads.single().toSet(),
            )
        }

    @Test
    fun `a standard form opened from the All tab reads itself and the unlisted all_forms leaves`() =
        runTest(dispatcher) {
            val general = FormSignatureBadges.UNIT_GENERAL
            val allForms = FormSignatureBadges.LEVEL_ALL_FORMS
            val badges = RecordingBadges().apply {
                live.value = listOf(leaf(general, allForms, "f1"), leaf(general, allForms, "old"))
            }
            val form = StandardForm(id = "f1", document = StoredDocument(media = "k/f1.pdf", name = "f1.pdf"))
            val vm = model(badges) { forms = listOf(form) }
            vm.onEvent(FormSignatureEvent.Open(FormSignScreen.StandardDocuments))
            runCurrent()
            vm.onEvent(FormSignatureEvent.OpenStandardForm(form))
            runCurrent()
            assertEquals(listOf("f1"), badges.formReads)
            assertEquals(listOf(listOf(leaf(general, allForms, "old"))), badges.leafReads)
        }

    @Test
    fun `the open room is read again when a message lands in it`() = runTest(dispatcher) {
        val general = FormSignatureBadges.UNIT_GENERAL
        val badges = RecordingBadges()
        val vm = model(badges) { unit = ChatUnit(id = "room") }
        vm.onEvent(FormSignatureEvent.Open(FormSignScreen.StandardDocuments))
        runCurrent()
        vm.onEvent(FormSignatureEvent.OpenChat)
        runCurrent()
        assertTrue(badges.chatReads.isEmpty(), "nothing unread yet")

        badges.live.value = listOf(leaf(general, "room", "m1"))
        runCurrent()
        assertEquals(listOf("room"), badges.chatReads)

        vm.onEvent(FormSignatureEvent.Back)
        runCurrent()
        badges.chatReads.clear()
        badges.live.value = listOf(leaf(general, "room", "m2"))
        runCurrent()
        assertTrue(badges.chatReads.isEmpty(), "a room no longer on screen waits for its next open")
    }

    @Test
    fun `a document's document_id is a second id form, and only a string is one`() {
        val json = Json { ignoreUnknownKeys = true }
        val both = json.decodeFromString(SignDocumentDto.serializer(), """{"_id":"s1","document_id":"d1"}""")
        assertEquals(listOf("s1", "d1"), both.toDomain()?.badgeIds)
        val odd = json.decodeFromString(SignDocumentDto.serializer(), """{"_id":"s1","document_id":{"x":1}}""")
        assertEquals(listOf("s1"), odd.toDomain()?.badgeIds)
    }
}
