package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.ui.EditorStep
import com.zillit.desktop.feature.esignature.ui.EsignEffect
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.EsignViewModel
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.PlacementMode
import com.zillit.desktop.feature.esignature.ui.SigningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The flows end to end against the fake service: lists, the editor's
 * save/send, the signer's consent and submit, templates and bulk send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EsignFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.effectsOf(model: EsignViewModel): MutableList<EsignEffect> {
        val seen = mutableListOf<EsignEffect>()
        backgroundScope.launch { model.effects.collect { seen += it } }
        return seen
    }

    @Test
    fun `start loads both scopes, and a socket pulse reloads them once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeEsignRepo(refreshes = events)
        val model = testModel(repo)
        model.start()
        runCurrent()
        assertEquals(7, repo.listLoads, "four sent buckets and three received")

        events.emit(Unit)
        runCurrent()
        assertEquals(14, repo.listLoads)

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(28, repo.listLoads, "a second start must not stack a second collector")
    }

    @Test
    fun `a receiver-only member lands on Sign Documents and cannot leave it`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val model = testModel(repo, viewer = EsignViewer(canView = true, canPost = false, ready = true))
        model.start()
        runCurrent()
        assertEquals(EsignSurface.Sign, model.state.value.surface)
        assertEquals(3, repo.listLoads, "only the received scope is fetched")
        model.onEvent(EsignEvent.SwitchSurface(EsignSurface.Templates))
        assertEquals(EsignSurface.Sign, model.state.value.surface)
    }

    @Test
    fun `the received bucket hides what I signed, and a draft opens in the editor`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val mine = Envelope(
            "e1",
            title = "Signed already",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r1", "u1", status = "signed")),
        )
        val waiting = Envelope(
            "e2",
            title = "Waiting",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r2", "u1")),
        )
        repo.receivedBuckets = mapOf("received" to listOf(mine, waiting))
        val draft = Envelope(
            "e3",
            title = "My draft",
            status = EnvelopeStatus.Draft,
            document = StoredFile("k/d.pdf", name = "d.pdf"),
        )
        repo.envelopesById["e3"] = draft
        repo.sentBuckets = mapOf("draft" to listOf(draft))
        val model = testModel(repo)
        model.start()
        runCurrent()

        assertEquals(listOf("e2"), model.state.value.signList.visibleRows.map { it.id })
        assertEquals(1, model.state.value.pendingForMe)

        model.onEvent(EsignEvent.OpenEnvelope(draft))
        runCurrent()
        assertEquals(EsignPageKind.Editor, model.state.value.page)
        assertEquals("e3", model.state.value.editor?.envelopeId)
        assertEquals(1, model.state.value.editor?.pages?.size, "the draft's document is rendered")
    }

    @Test
    fun `compose picks a PDF, adds people, places fields, saves a draft, then sends`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val model = testModel(repo)
        val effects = effectsOf(model)
        model.start()
        runCurrent()

        model.onEvent(EsignEvent.StartCompose)
        runCurrent()
        assertTrue(effects.any { it is EsignEffect.PickPdf })
        model.onEvent(EsignEvent.FilePicked("NDA.pdf", byteArrayOf(1)))
        runCurrent()
        val editor = model.state.value.editor
        assertNotNull(editor)
        assertEquals("NDA", editor.title)
        assertEquals(1, editor.pages.size)

        model.onEvent(EsignEvent.GoToPlace)
        runCurrent()
        assertTrue(effects.last() is EsignEffect.Failed, "no signer yet")
        model.onEvent(EsignEvent.ToggleSelfSign(true))
        model.onEvent(EsignEvent.AddSigner("u2"))
        model.onEvent(EsignEvent.AddCc("u2"))
        assertEquals(listOf(1, 2, 99), model.state.value.editor!!.recipients.map { it.routingOrder })
        model.onEvent(EsignEvent.MoveRecipient(1, up = true))
        assertEquals(listOf("u2", "u1"), model.state.value.editor!!.signers.map { it.userId })

        model.onEvent(EsignEvent.GoToPlace)
        assertEquals(EditorStep.Place, model.state.value.editor!!.step)
        model.onEvent(EsignEvent.ChoosePlacementMode(PlacementMode.FastPlace))
        model.onEvent(EsignEvent.ArmType(FieldType.SignHere))
        model.onEvent(EsignEvent.PageClicked(1, 300.0, 300.0))
        model.onEvent(EsignEvent.ArmSigner(1))
        model.onEvent(EsignEvent.PageClicked(1, 300.0, 500.0))
        assertEquals(listOf(0, 1), model.state.value.editor!!.fields.map { it.recipientIndex })

        model.onEvent(EsignEvent.SaveDraft)
        runCurrent()
        assertEquals(1, repo.created.size)
        assertEquals("s3/NDA.pdf", repo.created.single().document?.media, "the local PDF was uploaded first")
        assertEquals(EsignPageKind.Lists, model.state.value.page)

        // Reopen the saved draft and send it — a PUT, not a second create.
        model.onEvent(EsignEvent.OpenEnvelope(repo.envelopesById.values.single()))
        runCurrent()
        model.onEvent(EsignEvent.RequestSend)
        assertTrue(model.state.value.editor!!.confirmSend)
        model.onEvent(EsignEvent.ConfirmSend)
        runCurrent()
        assertEquals(1, repo.created.size)
        assertEquals(1, repo.updated.size)
        assertEquals(listOf("e100"), repo.sent)
        assertTrue(effects.last().let { it is EsignEffect.Notice && it.message.contains("counter-sign") })
    }

    @Test
    fun `manual placement asks who signs, and a template use fills slots with real people`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val template = EnvelopeTemplate(
            id = "t1", name = "Crew memo", documents = listOf(StoredFile("k/t.pdf", name = "t.pdf")),
            recipients = listOf(signer("", "").copy(email = "", placeholderLabel = "Crew member")),
            fields = listOf(field("f1", FieldType.SignHere, recipientId = "").copy(recipientIndex = 0)),
        )
        repo.templates += template
        val model = testModel(repo)
        model.start()
        runCurrent()

        model.onEvent(EsignEvent.UseTemplate(template))
        runCurrent()
        val editor = model.state.value.editor!!
        assertEquals("Crew memo", editor.fromTemplateName)
        assertTrue(editor.recipients.single().isPlaceholder)
        assertEquals("Crew member", editor.recipients.single().name)

        model.onEvent(EsignEvent.AddSigner("u2"))
        assertEquals("u2", model.state.value.editor!!.recipients.single().userId, "the slot is filled, not appended")
        assertEquals(0, model.state.value.editor!!.fields.single().recipientIndex, "the field still points at slot 0")

        model.onEvent(EsignEvent.GoToPlace)
        model.onEvent(EsignEvent.PageClicked(1, 200.0, 200.0))
        assertNotNull(model.state.value.editor!!.pending, "manual mode asks first")
        model.onEvent(EsignEvent.PlacePending(FieldType.Text))
        assertNull(model.state.value.editor!!.pending)
        assertEquals(2, model.state.value.editor!!.fields.size)
    }

    @Test
    fun `signing gates on consent, fills every owned field, and sends values`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val mark = SavedSignature("m1", isSignature = false, image = StoredFile("sig/i.png"))
        repo.marks = listOf(mark)
        val envelope = Envelope(
            "e1", title = "Memo", status = EnvelopeStatus.Sent, createdBy = "u9",
            recipients = listOf(signer("r1", "u1"), signer("r2", "u2", index = 1)),
            fields = listOf(
                field("a1", FieldType.InitialHere, "r1", page = 1, autoInitial = true),
                field("a2", FieldType.InitialHere, "r1", page = 1, autoInitial = true),
                field("n1", FieldType.Text, "r1").copy(label = "Name"),
                field("d1", FieldType.DateSigned, "r1"),
                field("x1", FieldType.SignHere, "r2"),
            ),
            document = StoredFile("k/m.pdf", name = "m.pdf"),
        )
        repo.envelopesById["e1"] = envelope
        val model = testModel(repo)
        val effects = effectsOf(model)
        model.start()
        runCurrent()

        model.onEvent(EsignEvent.OpenSigning(envelope, SigningMode.Sign))
        runCurrent()
        val signing = model.state.value.signing!!
        assertTrue(signing.needsConsent)
        assertEquals(listOf("e1"), repo.viewed, "opening marks it delivered")
        assertEquals(4, signing.myFields.size, "only my fields")
        assertEquals(3, signing.visibleFields.size, "auto initials collapse to one pad")
        assertFalse(signing.canFinish)

        repo.acceptFails = true
        model.onEvent(EsignEvent.Consent)
        runCurrent()
        assertTrue(model.state.value.signing!!.needsConsent, "the gate stays up when the server did not record it")
        assertTrue(effects.last() is EsignEffect.Failed)
        repo.acceptFails = false
        model.onEvent(EsignEvent.Consent)
        runCurrent()
        assertFalse(model.state.value.signing!!.needsConsent)
        assertEquals(listOf("e1"), repo.accepted)

        model.onEvent(EsignEvent.FinishSigning)
        assertTrue(repo.signed.isEmpty(), "required fields still empty")

        model.onEvent(EsignEvent.FocusField(0))
        model.onEvent(EsignEvent.UseSavedMark("m1"))
        runCurrent()
        val answers = model.state.value.signing!!.answers
        assertTrue(answers["a1"] is FieldAnswer.Mark, "the visible initial")
        assertTrue(answers["a2"] is FieldAnswer.Mark, "sign once covers the hidden initial")
        model.onEvent(EsignEvent.Answer("n1", FieldAnswer.Typed("Ada")))
        assertTrue(model.state.value.signing!!.canFinish)

        model.onEvent(EsignEvent.FinishSigning)
        runCurrent()
        val (id, fields) = repo.signed.single()
        assertEquals("e1", id)
        assertEquals(setOf("a1", "a2", "n1", "d1"), fields.map { it.tabId }.toSet())
        assertEquals("sig/i.png", (fields.first { it.tabId == "a2" }.answer as FieldAnswer.Mark).image.media)
        assertNull(fields.first { it.tabId == "d1" }.answer, "date signed is stamped by the service")
        assertTrue(model.state.value.signing!!.finished)
    }

    @Test
    fun `the pad draws, stores and saves a mark for later`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val envelope = Envelope(
            "e1",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r1", "u1").copy(acceptedTermsOn = 1L)),
            fields = listOf(field("s1", FieldType.SignHere, "r1")),
            document = StoredFile("k/m.pdf", name = "m.pdf"),
        )
        repo.envelopesById["e1"] = envelope
        val model = testModel(repo)
        model.start()
        runCurrent()
        model.onEvent(EsignEvent.OpenSigning(envelope, SigningMode.Sign))
        runCurrent()
        assertFalse(model.state.value.signing!!.needsConsent, "accepted on another device counts")

        model.onEvent(EsignEvent.OpenPad)
        assertEquals(PadMode.Draw, model.state.value.signing!!.pad!!.mode, "no saved marks, so draw")
        model.onEvent(EsignEvent.ApplyPad)
        assertTrue(model.state.value.signing!!.answers.isEmpty(), "nothing drawn, nothing applied")
        model.onEvent(EsignEvent.AddPadStroke(listOf(0f to 0f, 10f to 10f)))
        model.onEvent(EsignEvent.ApplyPad)
        runCurrent()
        assertTrue(model.state.value.signing!!.answers["s1"] is FieldAnswer.Mark)
        assertEquals(1, repo.marks.size, "saved for next time by default")
        assertTrue(repo.marks.single().isSignature)
    }

    @Test
    fun `declining, cancelling and reminding reach the service`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val envelope = Envelope(
            "e1",
            status = EnvelopeStatus.Sent,
            createdBy = "u1",
            recipients = listOf(signer("r2", "u2")),
            document = StoredFile("k/m.pdf", name = "m.pdf"),
        )
        repo.envelopesById["e1"] = envelope
        val model = testModel(repo)
        model.start()
        runCurrent()

        model.onEvent(EsignEvent.OpenEnvelope(envelope))
        runCurrent()
        val detail = model.state.value.detail!!
        assertTrue(detail.sentByMe)
        assertTrue(detail.canVoid)
        model.onEvent(EsignEvent.Remind("r2"))
        runCurrent()
        assertEquals(listOf<Pair<String, String?>>("e1" to "r2"), repo.reminded)
        model.onEvent(EsignEvent.Remind("r2"))
        runCurrent()
        assertEquals(1, repo.reminded.size, "the cooldown swallows the second press")

        model.onEvent(EsignEvent.StartVoid)
        model.onEvent(EsignEvent.ConfirmVoid)
        runCurrent()
        assertTrue(repo.voided.isEmpty(), "a reason is required")
        model.onEvent(EsignEvent.EditVoidReason("Wrong rate"))
        model.onEvent(EsignEvent.ConfirmVoid)
        runCurrent()
        assertEquals(listOf("e1" to "Wrong rate"), repo.voided)
        assertEquals(EsignPageKind.Lists, model.state.value.page)

        val theirs = Envelope(
            "e2",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r1", "u1")),
            fields = listOf(field("s", FieldType.SignHere, "r1")),
            document = StoredFile("k/m.pdf", name = "m.pdf"),
        )
        repo.envelopesById["e2"] = theirs
        model.onEvent(EsignEvent.OpenSigning(theirs, SigningMode.Sign))
        runCurrent()
        model.onEvent(EsignEvent.StartDecline)
        model.onEvent(EsignEvent.EditDeclineReason("Not my rate"))
        model.onEvent(EsignEvent.ConfirmDecline)
        runCurrent()
        assertEquals(listOf("e2" to "Not my rate"), repo.declined)
    }

    @Test
    fun `save as template strips people to slots, and bulk send ships only the sendable rows`() = runTest(dispatcher) {
        val repo = FakeEsignRepo()
        val model = testModel(repo)
        model.start()
        runCurrent()
        model.onEvent(EsignEvent.StartCompose)
        model.onEvent(EsignEvent.FilePicked("memo.pdf", byteArrayOf(1)))
        model.onEvent(EsignEvent.AddSigner("u2"))
        model.onEvent(EsignEvent.GoToPlace)
        model.onEvent(EsignEvent.ChoosePlacementMode(PlacementMode.FastPlace))
        model.onEvent(EsignEvent.PageClicked(1, 200.0, 200.0))
        model.onEvent(EsignEvent.OpenSaveAsTemplate)
        runCurrent()
        val sheet = model.state.value.editor!!.saveAsTemplate!!
        model.onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(name = "Memo", category = "deal_memo")))
        model.onEvent(EsignEvent.ConfirmSaveAsTemplate)
        runCurrent()
        val draft = repo.templateDrafts.single()
        assertEquals("Memo", draft.name)
        assertEquals("Bob Stone", draft.slots.single().name, "the slot keeps a label")
        assertNotNull(model.state.value.editor, "the envelope stays open after saving its template")

        val template = repo.templates.single()
        model.onEvent(EsignEvent.StartBulkSend(template))
        model.onEvent(EsignEvent.PickBulkCsv)
        val csvText = "name;email;rate\nJane;jane@x.io;100\nBad;;\nBob;bob@x.io;200\n"
        model.onEvent(EsignEvent.FilePicked("crew.csv", csvText.encodeToByteArray()))
        val send = model.state.value.bulk.send!!
        assertEquals(2, send.step)
        assertEquals(2, send.validCount)
        assertEquals(1, send.invalidCount)
        model.onEvent(EsignEvent.EditBatchName("Week 1"))
        model.onEvent(EsignEvent.ConfirmBulkSend)
        runCurrent()
        val (templateId, csv, name) = repo.bulkStarts.single()
        assertEquals(template.id, templateId)
        assertEquals("Week 1", name)
        assertEquals(
            "name,email,rate\nJane,jane@x.io,100\nBob,bob@x.io,200\n",
            csv,
            "re-serialised, comma-separated, invalid rows dropped",
        )
        assertEquals(EsignSurface.Bulk, model.state.value.surface)
        assertNull(model.state.value.bulk.send)
    }
}
