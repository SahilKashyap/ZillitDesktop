package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.common.ZillitError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole error path: key in, sentence out.
 *
 * Translating and substituting are separate steps and both are needed. Doing
 * only the first trades `timecard_cannot_edit_status` for
 * `Cannot edit this timecard because it is {{status}}`, which is not an
 * improvement anyone would notice.
 */
class LocalisedErrorTest {

    @BeforeTest
    fun install() {
        Labels.install(
            MutableStateFlow(
                LabelDictionary.Empty.with(
                    LabelKind.Messages,
                    mapOf(
                        "timecard_cannot_edit_status" to
                            "Cannot edit this timecard because it is {{status}}.",
                        "paid" to "Paid",
                        "trip_id_required" to "Pick a trip first.",
                    ),
                ),
            ),
        )
    }

    @AfterTest
    fun uninstall() = Labels.reset()

    private fun http(
        message: String?,
        elements: List<MessageElement> = emptyList(),
        status: Int = 400,
    ) = ZillitError.Http(status = status, serverMessage = message, messageElements = elements)

    @Test
    fun `a key with placeholders is translated and filled`() {
        val error = http(
            "timecard_cannot_edit_status",
            listOf(MessageElement("{{status}}", "paid")),
        )

        assertEquals("Cannot edit this timecard because it is Paid.", error.localised())
    }

    @Test
    fun `a value the dictionary does not know goes in as it stands`() {
        val error = http(
            "timecard_cannot_edit_status",
            listOf(MessageElement("{{status}}", "part-approved")),
        )

        assertEquals("Cannot edit this timecard because it is part-approved.", error.localised())
    }

    @Test
    fun `a key without placeholders is unaffected`() {
        assertEquals("Pick a trip first.", http("trip_id_required").localised())
    }

    @Test
    fun `a placeholder with no value left for it does not show its braces`() {
        // The server named a slot and sent nothing for it. An empty gap reads
        // as a clumsy sentence; `{{status}}` reads as a broken app.
        assertEquals(
            "Cannot edit this timecard because it is.",
            http("timecard_cannot_edit_status").localised(),
        )
    }

    @Test
    fun `the client's own wording is never sent through the dictionary`() {
        assertEquals("No internet connection.", ZillitError.NoConnection().localised())
        assertEquals(
            "Your session has expired. Please sign in again.",
            ZillitError.Unauthorized().localised(),
        )
    }

    @Test
    fun `a failure with no server message falls back to the client's`() {
        assertEquals("Something went wrong (500).", http(null, status = 500).localised())
    }

    @Test
    fun `substitution happens after translation, not before`() {
        // The placeholders live in the *translated* text. Substituting into the
        // key first would find nothing to replace, and the braces would survive
        // into the sentence.
        val error = http(
            "timecard_cannot_edit_status",
            listOf(MessageElement("{{status}}", "paid")),
        )

        assertEquals(false, error.localised().contains("{{"))
    }
}
