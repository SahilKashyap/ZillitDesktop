package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.ui.EditorRules
import com.zillit.desktop.feature.esignature.ui.EditorState
import com.zillit.desktop.feature.esignature.ui.flows.stripPlaceholders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The placer's geometry and the send gate — the web's rules, one assertion each. */
class EditorRulesTest {

    private val page = EsignPage(
        page = 1,
        imageBytes = ByteArray(0),
        widthPx = 612,
        heightPx = 792,
        widthPt = 612.0,
        heightPt = 792.0,
    )

    private fun signerAt(index: Int, email: String = "s$index@x.io") =
        EnvelopeRecipient(userId = "u$index", name = "Signer $index", email = email, routingOrder = index + 1)

    @Test
    fun `a field is centred on the click and kept inside the margins`() {
        val centred = EditorRules.place(
            FieldType.SignHere,
            page,
            xPt = 300.0,
            yPt = 400.0,
            owners = listOf(0),
        ) { "o" }.single()
        assertEquals(300.0 - 90.0, centred.x)
        assertEquals(400.0 - 18.0, centred.y)
        assertEquals(180.0, centred.width)

        val clamped = EditorRules.place(
            FieldType.SignHere,
            page,
            xPt = 5.0,
            yPt = 5.0,
            owners = listOf(0),
        ) { "o" }.single()
        assertEquals(20.0, clamped.x, "never past the left margin")
        assertEquals(0.0, clamped.y)
    }

    @Test
    fun `several owners land side by side, and choice fields arrive with two blank options`() {
        var n = 0
        val placed = EditorRules.place(FieldType.Radio, page, 300.0, 300.0, owners = listOf(0, 1)) { "opt${n++}" }
        assertEquals(2, placed.size)
        assertEquals(placed[0].x + 180.0 + 24.0, placed[1].x)
        assertEquals(listOf("opt0", "opt1"), placed[0].options.map { it.id })
        assertTrue(placed[0].options.all { it.label.isBlank() })
        assertEquals("", placed[0].label, "a radio starts unlabelled so the sender is nudged")
        assertEquals("Email", EditorRules.place(FieldType.Email, page, 300.0, 300.0, listOf(0)) { "o" }.single().label)
    }

    @Test
    fun `initials on every page put one auto initial per signer per page, replaceable`() {
        val pages = listOf(page, page.copy(page = 2))
        val manual = listOf(EnvelopeField(type = FieldType.SignHere, page = 1, recipientIndex = 0))
        val on = EditorRules.withAutoInitials(manual, pages, signerIndexes = listOf(0, 1), on = true)
        assertEquals(1 + 4, on.size)
        assertEquals(4, on.count { it.autoInitial })
        assertTrue(on.filter { it.autoInitial }.all { it.type == FieldType.InitialHere && it.y > 700 })
        val off = EditorRules.withAutoInitials(on, pages, listOf(0, 1), on = false)
        assertEquals(manual, off)
    }

    @Test
    fun `the send gate refuses in the web's order`() {
        val base = EditorState(document = StoredFile("k"), recipients = listOf(signerAt(0)))
        assertEquals("Please add a document to the envelope", EditorRules.problemBeforeSend(EditorState())?.text)
        val nobody = base.copy(recipients = emptyList())
        assertEquals("Please add at least one signer", EditorRules.problemBeforeSend(nobody)?.text)
        val slotOnly = base.copy(recipients = listOf(EditorRules.placeholder(0)))
        assertTrue(EditorRules.problemBeforeSend(slotOnly)!!.text.startsWith("Pick a real user for Signer 1"))
        assertTrue(EditorRules.problemBeforeSend(base)!!.text.contains("at least one signature or initial"))

        val withMark = base.copy(
            recipients = listOf(signerAt(0), signerAt(1)),
            fields = listOf(EnvelopeField(type = FieldType.SignHere, recipientIndex = 0)),
        )
        assertTrue(EditorRules.problemBeforeSend(withMark)!!.text.contains("Signer 1"), "the uncovered signer is named")

        val badOption = base.copy(
            fields = listOf(
                EnvelopeField(type = FieldType.SignHere, recipientIndex = 0),
                EnvelopeField(
                    type = FieldType.Dropdown,
                    recipientIndex = 0,
                    label = "Role",
                    options = listOf(FieldOption("a", "")),
                ),
            ),
        )
        val problem = EditorRules.problemBeforeSend(badOption)
        assertNotNull(problem)
        assertEquals(setOf(1), problem.invalidFields)

        val mark = EnvelopeField(type = FieldType.SignHere, recipientIndex = 0)
        val unlabelled = base.copy(fields = listOf(mark, EnvelopeField(type = FieldType.Text, recipientIndex = 0)))
        assertTrue(EditorRules.problemBeforeSend(unlabelled)!!.text.contains("has no label"))

        val labelled = EnvelopeField(type = FieldType.Text, recipientIndex = 0, label = "Name")
        val fine = base.copy(fields = listOf(mark, labelled))
        assertNull(EditorRules.problemBeforeSend(fine))
    }

    @Test
    fun `a draft needs only a subject`() {
        assertEquals("Please add an email subject", EditorRules.problemBeforeDraft(EditorState())?.text)
        assertNull(EditorRules.problemBeforeDraft(EditorState(title = "x")))
    }

    @Test
    fun `fields follow their owners when recipients are removed, swapped or stripped`() {
        val fields = listOf(
            EnvelopeField(type = FieldType.SignHere, recipientIndex = 0, label = "a"),
            EnvelopeField(type = FieldType.SignHere, recipientIndex = 1, label = "b"),
            EnvelopeField(type = FieldType.SignHere, recipientIndex = 2, label = "c"),
        )
        val afterRemove = EditorRules.fieldsAfterRemoving(fields, removedIndex = 1)
        assertEquals(listOf("a" to 0, "c" to 1), afterRemove.map { it.label to it.recipientIndex })

        val afterSwap = EditorRules.fieldsAfterSwap(fields, 0, 2)
        assertEquals(listOf("a" to 2, "b" to 1, "c" to 0), afterSwap.map { it.label to it.recipientIndex })

        val people = listOf(signerAt(0), EditorRules.placeholder(1), signerAt(2))
        val (kept, moved) = stripPlaceholders(people, fields)
        assertEquals(listOf("u0", "u2"), kept.map { it.userId })
        assertEquals(listOf(1, 2), kept.map { it.routingOrder }, "renumbered after the slot goes")
        assertEquals(listOf("a" to 0, "c" to 1), moved.map { it.label to it.recipientIndex })
    }

    @Test
    fun `moving and resizing stay on the page`() {
        val field = EnvelopeField(type = FieldType.Text, x = 500.0, y = 700.0, width = 200.0, height = 32.0)
        val moved = EditorRules.moved(field, page, dx = 500.0, dy = 500.0)
        assertEquals(612.0 - 200.0, moved.x)
        assertEquals(792.0 - 32.0, moved.y)
        val shrunk = EditorRules.resized(field, page, dw = -500.0, dh = -500.0)
        assertEquals(20.0, shrunk.width)
        assertEquals(20.0, shrunk.height)
    }
}
