package com.zillit.desktop.feature.location

import com.zillit.desktop.feature.location.data.parseLocationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A location record's discussion.
 *
 * The web runs this thread through a component shared with casting and
 * wardrobe; the desktop had no thread at all. These cover the reading half.
 */
class LocationMessageTest {

    private fun parse(
        body: String,
        decrypt: (String) -> String? = { it.removePrefix("enc:") },
        me: String? = "u1",
    ) = parseLocationMessage(Json.parseToJsonElement(body) as JsonObject, decrypt, me)

    /** The body is decrypted, and mine is marked as mine. */
    @Test
    fun `a line is decrypted and attributed`() {
        val message = parse("""{"_id":"m1","sender":"u1","message":"enc:on my way","created":1786950000000}""")

        assertEquals("on my way", message?.body)
        assertEquals("u1", message?.senderId)
        assertEquals(1_786_950_000_000, message?.sentAtMillis)
        assertTrue(message?.isMine == true)
    }

    /** Someone else's line is theirs. */
    @Test
    fun `another person's line is not mine`() {
        val message = parse("""{"_id":"m2","sender":"u9","message":"enc:ok"}""")

        assertFalse(message?.isMine == true)
    }

    /**
     * A body that will not decrypt keeps its row.
     *
     * Dropping it would silently renumber a thread people refer to by
     * position ("the third one down"), and the shape — who spoke, when — is
     * still true.
     */
    @Test
    fun `an undecryptable body keeps its line`() {
        val message = parse("""{"_id":"m3","sender":"u9","message":"garbage"}""", decrypt = { null })

        assertEquals("", message?.body)
        assertEquals("m3", message?.id)
    }

    /** The service spells the stamp two ways depending on the route. */
    @Test
    fun `either timestamp field is read`() {
        assertEquals(7L, parse("""{"_id":"m4","created_at":7}""")?.sentAtMillis)
        assertEquals(9L, parse("""{"_id":"m5","created":9}""")?.sentAtMillis)
    }

    /** A row nothing can address is dropped rather than guessed at. */
    @Test
    fun `an id-less row is dropped`() {
        assertNull(parse("""{"sender":"u1","message":"enc:hello"}"""))
    }

    /** Signed out, nothing is "mine" — and nothing pretends to be. */
    @Test
    fun `with no signed-in user nothing is mine`() {
        assertFalse(parse("""{"_id":"m6","sender":"u1"}""", me = null)?.isMine == true)
    }
}
