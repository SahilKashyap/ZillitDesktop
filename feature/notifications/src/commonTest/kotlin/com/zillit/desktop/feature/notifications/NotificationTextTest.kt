package com.zillit.desktop.feature.notifications

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.feature.notifications.domain.NotificationDecoder
import com.zillit.desktop.feature.notifications.domain.NotificationLabels
import com.zillit.desktop.feature.notifications.domain.NotificationWireText
import com.zillit.desktop.feature.notifications.domain.stripHtml
import com.zillit.desktop.feature.notifications.domain.toStampLabel
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The decoder against Android's `NotificationAdapter.onBind` and
 * `decodeSubLevelNotification`, on a fixed dictionary so the fallbacks are
 * visible: a key the dictionary lacks comes back as the key.
 */
class NotificationTextTest {

    private val dictionary = mapOf(
        "call_sheet_label" to "Call Sheet",
        "tools_label" to "Tools",
        "project_view_access_granted" to "View access granted",
        "chat_new_message" to "{{sender}} posted in {chat_label}",
        "chat_label" to "Chat",
        "event_reminder" to "{{title}} starts at {{event_time}}",
        "paid" to "Paid",
    )

    private val labels = object : NotificationLabels {
        override fun translate(key: String, preferMessages: Boolean) = dictionary[key] ?: key
        override fun exact(key: String) = dictionary[key]
    }

    private val decoder = NotificationDecoder(
        labels = labels,
        decrypt = { hex -> if (hex == CIPHER) "the crew call moved to seven" else null },
        formatEventTime = { "at $it" },
    )

    @Test
    fun `an access-grant action is prefixed with the path's last segment, translated`() {
        val text = decoder.text(
            NotificationWireText(
                message = "project_view_access_granted",
                action = "project_view_access_granted",
                path = "{tools_label/call_sheet_label}",
            ),
        )
        assertEquals("Call Sheet: View access granted", text)
    }

    @Test
    fun `slots are filled from the elements, a replacer that is a key is translated, single braces are keys`() {
        val text = decoder.text(
            NotificationWireText(
                message = "chat_new_message",
                elements = listOf(MessageElement(search = "{{sender}}", replacer = "Sam")),
            ),
        )
        assertEquals("Sam posted in Chat", text)
    }

    @Test
    fun `an event_time slot carrying a timestamp is formatted, an unmatched slot goes blank`() {
        val text = decoder.text(
            NotificationWireText(
                message = "event_reminder",
                elements = listOf(MessageElement(search = "{{event_time}}", replacer = "1700000000000")),
            ),
        )
        assertEquals("starts at at 1700000000000", text)
    }

    @Test
    fun `a replacer the dictionary knows is shown as its translation`() {
        val decoder = NotificationDecoder(labels = labels)
        val text = decoder.text(
            NotificationWireText(
                message = "chat_new_message",
                elements = listOf(MessageElement(search = "{{sender}}", replacer = "paid")),
            ),
        )
        assertEquals("Paid posted in Chat", text)
    }

    @Test
    fun `a body with no elements shaped like ciphertext is decrypted, a bad cipher shows the placeholder`() {
        assertEquals("the crew call moved to seven", decoder.text(NotificationWireText(message = CIPHER)))
        assertEquals(
            NotificationDecoder.UNREADABLE,
            decoder.text(NotificationWireText(message = "00112233445566778899aabbccddeeff")),
        )
    }

    @Test
    fun `a body with no elements that is a plain key goes to the dictionary`() {
        assertEquals("Tools", decoder.text(NotificationWireText(message = "tools_label")))
    }

    @Test
    fun `the encrypted flag decrypts before substituting`() {
        val decoder = NotificationDecoder(labels = labels, decrypt = { "{{who}} said hi" })
        val text = decoder.text(
            NotificationWireText(
                message = "whatever",
                elements = listOf(MessageElement(search = "{{who}}", replacer = "Sam")),
                encrypted = true,
            ),
        )
        assertEquals("Sam said hi", text)
    }

    @Test
    fun `the path is every segment translated and joined, whichever way it is braced`() {
        assertEquals("Tools : Call Sheet", decoder.pathLabel("{tools_label/call_sheet_label}"))
        assertEquals("Tools : Call Sheet", decoder.pathLabel("{tools_label}/{call_sheet_label}"))
        assertEquals("", decoder.pathLabel(""))
    }

    @Test
    fun `html is flattened to text`() {
        assertEquals("Sam posted\na line & more", "<b>Sam</b> posted<br/>a line &amp; more".stripHtml())
        assertEquals("plain", "plain".stripHtml())
    }

    @Test
    fun `the stamp is day-first with a 24-hour clock`() {
        // 2026-08-12T14:32:00Z
        assertEquals("12 Aug 2026, 14:32", 1_786_545_120_000L.toStampLabel(TimeZone.UTC))
        assertEquals("", 0L.toStampLabel(TimeZone.UTC))
    }

    private companion object {
        const val CIPHER = "6f1c2a3b4c5d6e7f8091a2b3c4d5e6f76f1c2a3b4c5d6e7f8091a2b3c4d5e6f7"
    }
}
