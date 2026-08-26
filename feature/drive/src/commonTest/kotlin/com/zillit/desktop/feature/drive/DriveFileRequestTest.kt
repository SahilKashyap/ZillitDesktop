package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.FileRequestDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drive file requests — an open invitation to put files in one folder.
 *
 * The desktop had no way to ask someone outside the production for files; the
 * routes were there and unused.
 */
class DriveFileRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json
        .decodeFromString(ListSerializer(FileRequestDto.serializer()), body)
        .mapNotNull { it.toDomain() }

    /** The everyday row, with the address people are actually sent. */
    @Test
    fun `a request carries its public address`() {
        val rows = parse(
            """
            [{"_id":"fr1","title":"Location stills","destination_folder_id":"f9",
              "url":"https://zillit.com/request/abc","upload_count":3}]
            """.trimIndent(),
        )

        val request = rows.single()
        assertEquals("Location stills", request.title)
        assertEquals("https://zillit.com/request/abc", request.link)
        assertEquals(3, request.uploadCount)
        assertTrue(!request.revoked)
    }

    /**
     * The address comes back under a different key depending on the route —
     * `url` from the create, `link` from the list — so both are read.
     */
    @Test
    fun `either address key is read`() {
        assertEquals(
            "https://z/link",
            parse("""[{"_id":"fr2","link":"https://z/link"}]""").single().link,
        )
        assertEquals(
            "https://z/public",
            parse("""[{"_id":"fr3","public_url":"https://z/public"}]""").single().link,
        )
    }

    /** Stamps arrive as ISO strings here and epoch millis elsewhere in the drive. */
    @Test
    fun `either timestamp form is read`() {
        val iso = parse("""[{"_id":"fr4","created_at":"2026-08-26T10:00:00.000Z"}]""").single()
        val millis = parse("""[{"_id":"fr5","created_at":1786950000000}]""").single()

        assertTrue(iso.createdAtMillis > 0, "an ISO stamp must not read as zero")
        assertEquals(1_786_950_000_000, millis.createdAtMillis)
    }

    /** A revoked request is kept and marked, not hidden — it explains itself. */
    @Test
    fun `a revoked request says so`() {
        assertTrue(parse("""[{"_id":"fr6","revoked":true}]""").single().revoked)
    }

    /** A row nothing can address is dropped rather than guessed at. */
    @Test
    fun `an id-less row is dropped`() {
        assertTrue(parse("""[{"title":"Ghost"}]""").isEmpty())
    }

    /** A request with no title still names itself on screen. */
    @Test
    fun `an untitled request gets a name`() {
        assertEquals("File request", parse("""[{"_id":"fr7"}]""").single().title)
        assertNull(parse("""[{"title":"x"}]""").firstOrNull())
    }
}
