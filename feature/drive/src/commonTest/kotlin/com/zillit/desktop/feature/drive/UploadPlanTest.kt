package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.UploadPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multipart chunking arithmetic.
 *
 * Pinned because it is a *prediction of the server's* rule: the presigned URLs
 * are signed for particular byte ranges, and a client that slices differently
 * gets a signature error from S3, not a size error. The symptom is "uploads
 * fail above 1 GB" with nothing in the message pointing here.
 */
class UploadPlanTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun `chunk size steps at the server's thresholds`() {
        assertEquals(8 * mb, UploadPlan.chunkSizeFor(1))
        assertEquals(8 * mb, UploadPlan.chunkSizeFor(100 * mb))
        // Exactly on a boundary belongs to the *lower* band — the server's
        // comparisons are `<=`, and an off-by-one here signs every part of a
        // 100 MB file for the wrong size.
        assertEquals(32 * mb, UploadPlan.chunkSizeFor(100 * mb + 1))
        assertEquals(32 * mb, UploadPlan.chunkSizeFor(gb))
        assertEquals(64 * mb, UploadPlan.chunkSizeFor(gb + 1))
        assertEquals(64 * mb, UploadPlan.chunkSizeFor(5 * gb))
        assertEquals(128 * mb, UploadPlan.chunkSizeFor(5 * gb + 1))
    }

    @Test
    fun `a file that divides evenly gets no trailing part`() {
        val plan = UploadPlan.forFile(24 * mb)

        assertEquals(8 * mb, plan.chunkSizeBytes)
        assertEquals(3, plan.totalParts)
        assertEquals(8 * mb, plan.sizeOf(3))
    }

    @Test
    fun `the last part is short, not padded`() {
        val plan = UploadPlan.forFile(20 * mb)

        assertEquals(3, plan.totalParts)
        assertEquals(8 * mb, plan.sizeOf(1))
        assertEquals(8 * mb, plan.sizeOf(2))
        // Sizing the tail as a full chunk is what produces an object S3
        // assembles without complaint and that opens as corrupt.
        assertEquals(4 * mb, plan.sizeOf(3))
    }

    @Test
    fun `ranges are contiguous and cover the whole file`() {
        val plan = UploadPlan.forFile(20 * mb)

        val ranges = (1..plan.totalParts).map { plan.rangeOf(it) }
        assertEquals(0, ranges.first().first)
        assertEquals(20 * mb - 1, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.last + 1, b.first, "parts must not overlap or leave a gap")
        }
    }

    @Test
    fun `an empty file still has one part`() {
        // S3 has no multipart upload with no parts; completing one with an
        // empty part list fails rather than creating an empty object.
        val plan = UploadPlan.forFile(0)

        assertEquals(1, plan.totalParts)
        assertEquals(0, plan.sizeOf(1))
    }

    @Test
    fun `a part number outside the plan is a programming error`() {
        val plan = UploadPlan.forFile(mb)

        assertFailsWith<IllegalArgumentException> { plan.rangeOf(0) }
        assertFailsWith<IllegalArgumentException> { plan.rangeOf(2) }
    }

    @Test
    fun `progress runs from nothing to everything`() {
        val plan = UploadPlan.forFile(20 * mb)

        assertEquals(0f, plan.progress(0))
        assertEquals(1f, plan.progress(plan.totalParts))
        // Clamped: a server that acknowledges a part twice must not report 133%.
        assertEquals(1f, plan.progress(plan.totalParts * 2))
    }

    @Test
    fun `an oversized file is refused before a session is opened`() {
        assertNull(UploadPlan.rejectionReason("dailies.mov", 9 * gb))

        val reason = assertNotNull(
            UploadPlan.rejectionReason("dailies.mov", UploadPlan.MAX_FILE_BYTES + 1),
        )
        // Naming the file matters when ten were dropped at once.
        assertTrue(reason.contains("dailies.mov"))
    }

    @Test
    fun `a 10 GB file stays within S3's part ceiling`() {
        val plan = UploadPlan.forFile(UploadPlan.MAX_FILE_BYTES)

        // S3 caps a multipart upload at 10,000 parts. The adaptive sizing
        // exists to keep the largest permitted file well under it.
        assertTrue(plan.totalParts <= 10_000, "got ${plan.totalParts} parts")
        assertEquals(128 * mb, plan.chunkSizeBytes)
    }
}
