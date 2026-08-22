package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.readFolder
import com.zillit.desktop.feature.email.data.readMessage
import com.zillit.desktop.feature.email.data.readSummary
import com.zillit.desktop.feature.email.data.readUid
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the mail API's JSON.
 *
 * Every case here is a shape the server actually sends. Typed DTOs have failed
 * against HTTP 200 on this API repeatedly, which is why these readers coerce
 * rather than deserialise — and why they are worth pinning down.
 */
class MailReaderTest {

    private fun folder(json: String) = readFolder(Json.parseToJsonElement(json))
    private fun summary(json: String) = readSummary(Json.parseToJsonElement(json))
    private fun uid(json: String) = readUid(Json.parseToJsonElement(json))

    @Test
    fun `has_attachments arrives as a string, not a boolean`() {
        // `"false"` is a non-empty string and therefore truthy almost
        // everywhere. Every message would claim attachments.
        val none = summary("""{"id":"m1","has_attachments":"false","attachment_count":0}""")!!
        val some = summary("""{"id":"m2","has_attachments":"true","attachment_count":2}""")!!

        assertFalse(none.hasAttachments)
        assertTrue(some.hasAttachments)
        assertEquals(2, some.attachmentCount)
    }

    @Test
    fun `a real boolean is also accepted`() {
        assertTrue(summary("""{"id":"m1","has_attachments":true}""")!!.hasAttachments)
    }

    @Test
    fun `recipients come as an array, a joined string, or wrapped objects`() {
        val array = summary("""{"id":"m1","to":["a@b.com","c@d.com"]}""")!!
        val joined = summary("""{"id":"m2","to":"a@b.com, c@d.com"}""")!!
        val wrapped = summary(
            """{"id":"m3","to":[{"email_address":"a@b.com"},{"email_address":"c@d.com"}]}""",
        )!!

        val expected = listOf("a@b.com", "c@d.com")
        assertEquals(expected, array.to)
        assertEquals(expected, joined.to)
        assertEquals(expected, wrapped.to, "the send payload's shape comes back on some rows")
    }

    @Test
    fun `a message with no thread is a thread of one`() {
        // A blank thread id would collapse every such message together.
        val message = summary("""{"id":"m1","subject":"Hi"}""")!!

        assertEquals("m1", message.threadId)
    }

    @Test
    fun `a row with no id is dropped rather than rendered blank`() {
        assertNull(summary("""{"subject":"Orphan"}"""))
        assertNull(summary("""[]"""))
    }

    @Test
    fun `a deleted folder is a tombstone, not a folder`() {
        // The server keeps them so other devices can drop the folder too.
        // Showing them resurrects folders the user deleted somewhere else.
        assertNull(folder("""{"folder_name":"Old","deleted":1}"""))
        assertEquals("Keep", folder("""{"folder_name":"Keep","deleted":0}""")!!.name)
    }

    @Test
    fun `INBOX is displayed as Inbox`() {
        val inbox = folder("""{"folder_name":"INBOX","system_defined":true}""")!!

        assertEquals("INBOX", inbox.name, "the wire name is what every call takes")
        assertEquals("Inbox", inbox.displayName)
    }

    @Test
    fun `a user folder keeps the capitalisation its owner chose`() {
        val theirs = folder("""{"folder_name":"vfx notes","system_defined":false}""")!!

        assertEquals("vfx notes", theirs.displayName, "renaming someone's folder is not our business")
    }

    @Test
    fun `uids read from objects, bare numbers and numeric strings`() {
        assertEquals(12, uid("""{"uid":12}"""))
        assertEquals(12, uid("""12"""))
        assertEquals(12, uid(""""12""""))
        assertNull(uid("""{"uid":0}"""), "uid 0 is the placeholder for unsent mail")
        assertNull(uid("""{}"""))
    }

    @Test
    fun `a summary remembers which folder it came from`() {
        // Without it the message cannot be opened: the trail call needs a folder.
        val message = summary("""{"id":"m1","folder_name":"Sent","uid":7}""")!!

        assertEquals("Sent", message.folderName)
        assertEquals(7, message.uid)
    }

    @Test
    fun `an html body is flattened for the list snippet`() {
        val message = summary("""{"id":"m1","text":"<p>Call is <b>6am</b></p>"}""")!!

        assertFalse(message.snippet.contains("<"), "tag soup in a three-line preview")
        assertTrue(message.snippet.contains("6am"))
    }

    @Test
    fun `the references chain reads as an array, a spaced string or a comma string`() {
        // Three shapes from three writers — Android carries a
        // `FlexibleStringListAdapter` for exactly this. Losing the chain to a
        // shape costs the reply its conversation.
        fun chain(json: String) = readMessage(Json.parseToJsonElement(json))?.references

        assertEquals(listOf("m1", "m2"), chain("""{"id":"m3","references":["m1","m2"]}"""))
        assertEquals(listOf("m1", "m2"), chain("""{"id":"m3","references":"m1 m2"}"""))
        assertEquals(listOf("m1", "m2"), chain("""{"id":"m3","references":"m1,m2"}"""))
        assertEquals(emptyList(), chain("""{"id":"m3"}"""))
    }

    @Test
    fun `a message with no trail id is threaded by its chain, then its parent`() {
        // The phones' `calculateThreadId`: the root of the chain, else the
        // message it answers, else itself.
        fun thread(json: String) = readMessage(Json.parseToJsonElement(json))?.threadId

        assertEquals("m1", thread("""{"id":"m3","references":["m1","m2"]}"""))
        assertEquals("m2", thread("""{"id":"m3","in_reply_to":"m2"}"""))
        assertEquals("m3", thread("""{"id":"m3"}"""))
        // A trail the server computed still wins over both.
        assertEquals(
            "t9",
            thread("""{"id":"m3","thread_id":"t9","references":["m1"]}"""),
        )
    }
}
