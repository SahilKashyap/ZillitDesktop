package com.zillit.desktop.feature.esignature

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.BulkJobRow
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.ui.BulkSendState
import com.zillit.desktop.feature.esignature.ui.BulkState
import com.zillit.desktop.feature.esignature.ui.DetailState
import com.zillit.desktop.feature.esignature.ui.EditorState
import com.zillit.desktop.feature.esignature.ui.EditorStep
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignScreen
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.ListLayout
import com.zillit.desktop.feature.esignature.ui.ManageInnerTab
import com.zillit.desktop.feature.esignature.ui.ManageState
import com.zillit.desktop.feature.esignature.ui.MarksState
import com.zillit.desktop.feature.esignature.ui.PadState
import com.zillit.desktop.feature.esignature.ui.ParsedCsv
import com.zillit.desktop.feature.esignature.ui.SaveTemplateDraft
import com.zillit.desktop.feature.esignature.ui.SignListState
import com.zillit.desktop.feature.esignature.ui.SigningMode
import com.zillit.desktop.feature.esignature.ui.SigningState
import com.zillit.desktop.feature.esignature.ui.TemplatesState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Composes every surface the module has, in both themes where the layout
 * is table-shaped. A screen that throws on open — an infinite-constraint
 * table, a weighted child in a scroll — fails here, not on the user.
 */
@OptIn(ExperimentalTestApi::class)
class EsignScreenRenderTest {

    private val page: EsignPage by lazy {
        val image = BufferedImage(306, 396, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        EsignPage(
            page = 1,
            imageBytes = out.toByteArray(),
            widthPx = 306,
            heightPx = 396,
            widthPt = 612.0,
            heightPt = 792.0,
        )
    }

    private val sent = Envelope(
        "e1", title = "Crew deal memo", status = EnvelopeStatus.Sent, createdBy = "u1", sentOn = 1_700_000_000_000L,
        recipients = listOf(signer("r1", "u2"), signer("r2", "u3", status = "signed", index = 1)),
        fields = listOf(field("f1", FieldType.SignHere, "r1"), field("f2", FieldType.Text, "r2").copy(label = "Role")),
        document = StoredFile("k/memo.pdf", name = "memo.pdf", pageCount = 3, sizeBytes = 200_000),
    )
    private val draft = Envelope("e2", title = "NDA draft", status = EnvelopeStatus.Draft)
    private val received = Envelope(
        "e3",
        title = "Location release",
        status = EnvelopeStatus.Sent,
        recipients = listOf(signer("r9", "u1")),
        fields = listOf(field("g1", FieldType.SignHere, "r9")),
    )

    private fun base() = EsignUiState(
        viewer = EsignViewer(canView = true, canPost = true, canDownload = true, ready = true),
        currentUserId = "u1",
        manage = ManageState(
            buckets = mapOf(
                "sent" to listOf(sent),
                "draft" to listOf(draft),
                "completed" to listOf(sent.copy(status = EnvelopeStatus.Completed)),
            ),
        ),
        signList = SignListState(buckets = mapOf("received" to listOf(received))),
        pendingForMe = 1,
    )

    /** Composes [state]; [expect] names a text that must be on screen. */
    private fun render(state: EsignUiState, dark: Boolean = false, expect: String? = null) {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = dark) { EsignScreen(state = state, onEvent = {}) } }
            expect?.let { onNodeWithText(it).assertExists() }
        }
    }

    @Test
    fun `the manager's lists draw as table and cards in both themes`() {
        listOf(false, true).forEach { dark ->
            render(base(), dark, expect = "Crew deal memo")
            render(base().copy(layout = ListLayout.Card), dark, expect = "Crew deal memo")
        }
        val drafts = base().copy(manage = base().manage.copy(inner = ManageInnerTab.Draft))
        render(drafts, expect = "NDA draft")
    }

    @Test
    fun `the sign list and the receiver-only branch compose`() {
        render(base().copy(surface = EsignSurface.Sign), expect = "Location release")
        val receiver = EsignViewer(canView = true, canPost = false, ready = true)
        render(
            base().copy(viewer = receiver, surface = EsignSurface.Sign, layout = ListLayout.Card),
            expect = "Location release",
        )
        render(EsignUiState(viewer = EsignViewer(canView = false, canPost = false, ready = true)))
    }

    @Test
    fun `the editor composes on both steps with a page and a selected field`() {
        val prepare = EditorState(
            fileName = "memo.pdf",
            pendingBytes = byteArrayOf(1),
            pages = listOf(page),
            title = "Memo",
            recipients = sent.recipients,
            options = emptyList(),
        )
        // "Next: Place Fields": the Android key's capitalisation, kept for its 21 translations.
        render(base().copy(page = EsignPageKind.Editor, editor = prepare), expect = "Next: Place Fields")
        val place = prepare.copy(
            step = EditorStep.Place,
            modeAsked = true,
            fields = listOf(field("f", FieldType.Dropdown, "").copy(recipientIndex = 0, label = "Role")),
            selectedField = 0,
        )
        // "Delete Field": the Android key's capitalisation, kept for its 21 translations.
        render(base().copy(page = EsignPageKind.Editor, editor = place), expect = "Delete Field")
        val asking = place.copy(modeAsked = false, selectedField = null)
        render(base().copy(page = EsignPageKind.Editor, editor = asking), expect = "How is your document set up?")
        val sheet = SaveTemplateDraft(name = "x")
        val template = place.copy(isTemplate = true, selectedField = null, saveAsTemplate = sheet)
        render(base().copy(page = EsignPageKind.Editor, editor = template))
    }

    @Test
    fun `the tracker composes with its audit trail, order and cancel dialogs`() {
        val detail = DetailState(
            envelope = sent,
            loading = false,
            sentByMe = true,
            audit = listOf(AuditEntry("envelope_sent", "Ada", happenedOn = 1_700_000_000_000L)),
        )
        // "Recipient Activity": the Android key's capitalisation, kept for its 21 translations.
        render(base().copy(page = EsignPageKind.Detail, detail = detail), expect = "Recipient Activity")
        render(
            base().copy(page = EsignPageKind.Detail, detail = detail.copy(showOrder = true, voiding = true)),
            dark = true,
        )
    }

    @Test
    fun `the signing surface composes gated, mid-signing, read-only and finished`() {
        val me = received.recipients.single()
        val gated = SigningState(
            envelope = received,
            me = me,
            pages = listOf(page),
            loadingPages = false,
            myFields = received.fields,
            needsConsent = true,
        )
        render(base().copy(page = EsignPageKind.Signing, signing = gated), expect = "Digital Signature Request")
        val open = gated.copy(
            needsConsent = false,
            answers = mapOf("g1" to FieldAnswer.Mark(StoredFile("sig/x.png"))),
            pad = PadState(),
        )
        render(base().copy(page = EsignPageKind.Signing, signing = open), expect = "Done & Next")
        render(
            base().copy(
                page = EsignPageKind.Signing,
                signing = gated.copy(needsConsent = false, mode = SigningMode.ViewSigned, envelope = sent),
            ),
            dark = true,
        )
        val finished = open.copy(finished = true, pad = null)
        render(base().copy(page = EsignPageKind.Signing, signing = finished), expect = "Signing complete")
    }

    @Test
    fun `templates, bulk sends and the marks dialog compose`() {
        val template = EnvelopeTemplate(
            "t1",
            name = "Crew NDA",
            category = "nda",
            documents = listOf(StoredFile("k/t.pdf", name = "t.pdf", pageCount = 2)),
            fields = listOf(field("f", FieldType.SignHere, "")),
        )
        val library = TemplatesState(items = listOf(template), loaded = true)
        render(base().copy(surface = EsignSurface.Templates, templates = library), expect = "Crew NDA")
        val asList = library.copy(layout = ListLayout.List, detail = template)
        render(base().copy(surface = EsignSurface.Templates, templates = asList))
        val job = BulkJob(
            "j1",
            name = "Week 1",
            status = "completed",
            totalRows = 2,
            processed = 2,
            succeeded = 1,
            failed = 1,
            rows = listOf(
                BulkJobRow(0, "sent", "e1", name = "Jane", email = "jane@x.io"),
                BulkJobRow(1, "failed", error = "bad email"),
            ),
        )
        val dashboard = BulkState(jobs = listOf(job), loaded = true, open = job)
        render(base().copy(surface = EsignSurface.Bulk, bulk = dashboard), expect = "Remind outstanding")
        val csv = ParsedCsv(listOf("name", "email"), listOf(mapOf("name" to "Jane", "email" to "jane@x.io")), ',')
        val sending = BulkState(loaded = true, send = BulkSendState(template = template, step = 2, parsed = csv))
        render(base().copy(surface = EsignSurface.Bulk, bulk = sending))
        render(base().copy(marks = MarksState(open = true)))
    }
}
