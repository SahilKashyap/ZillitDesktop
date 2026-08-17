package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.readMessage
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.asThread
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a conversation.
 */
class ThreadTest {

    private fun read(json: String) = readMessage(Json.parseToJsonElement(json))

    private fun message(id: String, at: Long) =
        EmailMessage(id = id, threadId = "t", subject = "s", from = "a@b.com", receivedAtMillis = at)

    @Test
    fun `a thread reads oldest first`() {
        // A reply makes no sense before the message it answers.
        val thread = listOf(message("reply", 300), message("original", 100)).asThread()

        assertEquals(listOf("original", "reply"), thread.map { it.id })
    }

    @Test
    fun `a full message reads`() {
        val message = read(
            """{"_id":"m1","thread_id":"t1","subject":"Call sheet","from":"Aisha <a@prod.com>",
               "to":["crew@prod.com"],"cc":["ad@prod.com"],"body":"See attached",
               "created_at":1700000000000,"html_email":false}""",
        )!!

        assertEquals("m1", message.id)
        assertEquals("Aisha", message.senderName)
        assertEquals("a@prod.com", message.senderAddress)
        assertEquals(listOf("crew@prod.com", "ad@prod.com"), message.recipients)
        assertFalse(message.isHtml)
    }

    @Test
    fun `markup is detected even when html_email is unset`() {
        // Rendering those raw would show the tags to the reader.
        val message = read("""{"_id":"m1","body":"<p>Hello</p>","html_email":false}""")!!

        assertTrue(message.isHtml, "markup should be converted regardless of the flag")
    }

    @Test
    fun `plain text is not mistaken for markup`() {
        val message = read("""{"_id":"m1","body":"Costs are 3 < 5 and rising"}""")!!

        assertFalse(message.isHtml)
    }

    @Test
    fun `recipients arrive as an array or a comma-separated string`() {
        assertEquals(listOf("a@b.com", "c@d.com"), read("""{"_id":"m","to":["a@b.com","c@d.com"]}""")!!.to)
        assertEquals(listOf("a@b.com", "c@d.com"), read("""{"_id":"m","to":"a@b.com, c@d.com"}""")!!.to)
        assertEquals(emptyList(), read("""{"_id":"m"}""")!!.to)
    }

    @Test
    fun `a message with no thread id is a thread of one`() {
        // A blank id would collapse every such message into one conversation.
        assertEquals("m1", read("""{"_id":"m1"}""")!!.threadId)
    }

    @Test
    fun `a message with no id is dropped`() {
        assertNull(read("""{"subject":"orphan"}"""))
        assertNull(read("""["not","an","object"]"""))
    }

    @Test
    fun `attachments are read, and a nameless one still shows`() {
        val message = read(
            """{"_id":"m","attachments":[
               {"attachment_id":"a1","file_name":"callsheet.pdf","content_length":2048},
               {"attachment_id":"a2"}]}""",
        )!!

        assertEquals(2, message.attachments.size)
        assertEquals("callsheet.pdf", message.attachments[0].fileName)
        assertEquals("2 KB", message.attachments[0].readableSize)
        assertEquals("file", message.attachments[1].fileName, "a nameless attachment is still downloadable")
    }

    @Test
    fun `attachment sizes read at a human scale`() {
        assertEquals("512 B", EmailAttachment("a", "f", sizeBytes = 512).readableSize)
        assertEquals("2 KB", EmailAttachment("a", "f", sizeBytes = 2048).readableSize)
        assertEquals("1.5 MB", EmailAttachment("a", "f", sizeBytes = 1_572_864).readableSize)
        assertEquals("", EmailAttachment("a", "f", sizeBytes = 0).readableSize, "unknown size shows nothing")
    }

    @Test
    fun `a message never prints its body or subject`() {
        val text = message("m", 1).copy(subject = "Budget", body = "confidential").toString()

        assertFalse(text.contains("Budget"))
        assertFalse(text.contains("confidential"))
    }
}
