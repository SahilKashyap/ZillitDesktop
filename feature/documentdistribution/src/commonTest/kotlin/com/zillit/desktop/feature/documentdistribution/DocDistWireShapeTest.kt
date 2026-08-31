package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.data.decodeDistributions
import com.zillit.desktop.feature.documentdistribution.data.decodeFolders
import com.zillit.desktop.feature.documentdistribution.data.decodeLibraryPage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three fields this module read under the wrong name, pinned against
 * captured dev responses.
 *
 * All three decoded *successfully* and rendered blank, which is why nothing
 * caught them: a document called "Untitled", a folder with no date, a send with
 * no timestamp. Only looking at the running app found them (2026-08-11).
 */
class DocDistWireShapeTest {

    @Test
    fun `a document is named by original_name, not name`() {
        val page = decodeLibraryPage(
            """
            {"documents":[{
              "_id": "d1",
              "original_name": "Call Sheet Day 12.pdf",
              "content_type": "application/pdf",
              "media_type": "document",
              "file_size": 482000,
              "document_date": "2026-08-11"
            }],"total":1}
            """.trimIndent(),
        )

        assertEquals("Call Sheet Day 12.pdf", page.documents.single().name)
        // The MIME still wins for the kind, so a PDF gets its own badge.
        assertEquals("2026-08-11", page.documents.single().documentDate)
    }

    @Test
    fun `a from-tool document falls back to name`() {
        // `/documents/from-tool` registers a display name rather than an
        // uploaded filename, so the fallback is not dead code.
        val page = decodeLibraryPage("""{"documents":[{"_id":"d1","name":"Crew List"}]}""")

        assertEquals("Crew List", page.documents.single().name)
    }

    @Test
    fun `a document with neither name reads Untitled rather than blank`() {
        val page = decodeLibraryPage("""{"documents":[{"_id":"d1"}]}""")

        assertEquals("Untitled", page.documents.single().name)
    }

    @Test
    fun `a folder carries a production date, not a created stamp`() {
        // `created_at` does not exist on this collection; reading one left the
        // column empty on every row.
        val folders = decodeFolders(
            """[{"_id":"f1","name":"Call Sheets","folder_date":"2026-08-11"}]""",
        )

        assertEquals("2026-08-11", folders.single().folderDate)
    }

    @Test
    fun `a folder with no date is blank rather than a wrong one`() {
        val folders = decodeFolders("""[{"_id":"f1","name":"Loose"}]""")

        assertEquals("", folders.single().folderDate)
    }

    @Test
    fun `a folder date carrying a full timestamp is trimmed to the day`() {
        val folders = decodeFolders(
            """[{"_id":"f1","name":"X","folder_date":"2026-08-11T09:00:00.000Z"}]""",
        )

        assertEquals("2026-08-11", folders.single().folderDate)
    }

    @Test
    fun `a distribution is stamped by created, as a number or a string`() {
        val asNumber = decodeDistributions("""[{"_id":"s1","subject":"A","created":1754000000000}]""")
        val asString = decodeDistributions("""[{"_id":"s1","subject":"A","created":"1754000000000"}]""")

        assertEquals(1_754_000_000_000, asNumber.single().sentAt)
        assertEquals(1_754_000_000_000, asString.single().sentAt)
    }

    @Test
    fun `an unsent-looking distribution still lists, with no stamp`() {
        val rows = decodeDistributions("""[{"_id":"s1","subject":"A"}]""")

        assertEquals(null, rows.single().sentAt)
        assertEquals("A", rows.single().subject)
    }

    @Test
    fun `an absent opened flag is unknown, not unopened`() {
        val rows = decodeDistributions(
            """[{"_id":"s1","subject":"A","recipients":[{"email":"a@b.co"}]}]""",
        )

        // Reporting a fresh send as ignored is worse than reporting nothing.
        assertEquals(
            com.zillit.desktop.feature.documentdistribution.domain.OpenState.Unknown,
            rows.single().recipients.single().state,
        )
        assertTrue(rows.single().openSummary.startsWith("0 of 1"))
    }
    /**
     * The S3 object is nested under `attachment`, which is where both phones
     * read it (`Document.attachment`, `isS3 = attachment != null`).
     *
     * This port read a top-level `media` instead and so found storage on no
     * document at all — invisible, because the only thing that used it was a
     * call to an endpoint that did not exist either.
     */
    @Test
    fun `a document's storage is read from the nested attachment`() {
        val page = decodeLibraryPage(
            """
            {"documents":[{
              "_id": "d1",
              "original_name": "Day 11.pdf",
              "attachment": {
                "media": "documents/day 11.pdf",
                "bucket": "zillit-prod",
                "region": "eu-west-2"
              }
            }]}
            """.trimIndent(),
        )

        val storage = page.documents.single().storage
        assertNotNull(storage)
        assertEquals("documents/day 11.pdf", storage.key)
        assertEquals("zillit-prod", storage.bucket)
        assertEquals("eu-west-2", storage.region)
    }

    /** A LOCAL production sends no attachment, and that is not a parse failure. */
    @Test
    fun `a document with no attachment simply has no storage`() {
        val page = decodeLibraryPage(
            """{"documents":[{"_id": "d1", "original_name": "Day 11.pdf"}]}""",
        )

        assertNull(page.documents.single().storage)
    }

}
