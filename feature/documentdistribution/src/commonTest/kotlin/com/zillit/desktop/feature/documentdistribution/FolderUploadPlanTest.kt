package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.FolderEntry
import com.zillit.desktop.feature.documentdistribution.domain.UploadBatch
import com.zillit.desktop.feature.documentdistribution.domain.buildUploadPlan
import com.zillit.desktop.feature.documentdistribution.domain.countPlannedUnder
import com.zillit.desktop.feature.documentdistribution.domain.partitionEntries
import com.zillit.desktop.feature.documentdistribution.domain.treeRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The folder-upload plan, ported from the web's `folderUpload.js` and `uploadBatch.js`. */
class FolderUploadPlanTest {

    private fun entry(path: String, type: String = "application/pdf", size: Long = 10) =
        FolderEntry(path, type, size) { ByteArray(0) }

    // -- the plan -------------------------------------------------------------------

    @Test
    fun `a folder implies its parents, shallowest first`() {
        val plan = buildUploadPlan(listOf(entry("Docs/Sub/Deep/a.pdf"), entry("Docs/b.pdf")))
        assertEquals(listOf("Docs", "Docs/Sub", "Docs/Sub/Deep"), plan.folders)
    }

    @Test
    fun `siblings at one depth are alphabetical, regardless of the order they were found`() {
        val plan = buildUploadPlan(listOf(entry("R/zed/a.pdf"), entry("R/Alpha/b.pdf"), entry("R/mid/c.pdf")))
        assertEquals(listOf("R", "R/Alpha", "R/mid", "R/zed"), plan.folders)
    }

    @Test
    fun `an empty folder is created too, with its own ancestors`() {
        val plan = buildUploadPlan(listOf(entry("Docs/a.pdf")), emptyFolders = setOf("Docs/Later/Nothing"))
        assertEquals(listOf("Docs", "Docs/Later", "Docs/Later/Nothing"), plan.folders)
        assertEquals(1, plan.fileCount)
    }

    @Test
    fun `loose files make a flat plan that needs no confirmation`() {
        val plan = buildUploadPlan(listOf(entry("a.pdf"), entry("b.pdf")))
        assertTrue(plan.isFlat)
        assertEquals(0, plan.folderCount)
        assertFalse(buildUploadPlan(listOf(entry("F/a.pdf"))).isFlat)
    }

    @Test
    fun `an empty folder alone is not flat`() {
        assertFalse(buildUploadPlan(emptyList(), setOf("Empty")).isFlat)
    }

    @Test
    fun `the plan totals what it will send, folders included`() {
        val plan = buildUploadPlan(listOf(entry("A/x.pdf", size = 5), entry("A/B/y.pdf", size = 7)))
        assertEquals(2, plan.folderCount)
        assertEquals(2, plan.fileCount)
        assertEquals(12, plan.bytes)
        assertEquals(4, plan.requestCount)
    }

    // -- the allow-list -----------------------------------------------------------------

    @Test
    fun `unsupported files are reported, not uploaded`() {
        val (accepted, rejected) = partitionEntries(
            listOf(entry("A/x.pdf"), entry("A/run.exe", "application/octet-stream"), entry("A/pic.JPG", "image/jpeg")),
        )
        assertEquals(listOf("A/x.pdf", "A/pic.JPG"), accepted.map { it.relativePath })
        assertEquals(listOf("A/run.exe"), rejected.map { it.relativePath })
    }

    @Test
    fun `a rejected file does not create its folder`() {
        val (accepted, _) = partitionEntries(listOf(entry("Junk/run.exe", "application/octet-stream")))
        assertTrue(buildUploadPlan(accepted).isFlat)
    }

    // -- a failed folder takes its subtree with it ------------------------------------------

    @Test
    fun `a folder's subtree is its sub-folders and every file under any of them`() {
        val plan = buildUploadPlan(
            listOf(entry("A/1.pdf"), entry("A/B/2.pdf"), entry("A/B/C/3.pdf"), entry("Other/4.pdf")),
        )
        // A: sub-folders B, B/C (2) + files 1, 2, 3 (3) — the folder itself is not counted.
        assertEquals(5, countPlannedUnder(plan, "A"))
        assertEquals(3, countPlannedUnder(plan, "A/B"))
        assertEquals(1, countPlannedUnder(plan, "A/B/C"))
        assertEquals(1, countPlannedUnder(plan, "Other"))
    }

    @Test
    fun `a name that merely starts the same is not under it`() {
        val plan = buildUploadPlan(listOf(entry("Doc/1.pdf"), entry("Docs/2.pdf")))
        assertEquals(1, countPlannedUnder(plan, "Doc"))
    }

    @Test
    fun `a blank path counts nothing`() {
        assertEquals(0, countPlannedUnder(buildUploadPlan(listOf(entry("A/1.pdf"))), ""))
    }

    // -- the confirmation tree ----------------------------------------------------------------

    @Test
    fun `the tree shows sub-folders before files, each indented under its folder`() {
        val rows = buildUploadPlan(listOf(entry("A/1.pdf"), entry("A/B/2.pdf"), entry("A/z.pdf"))).treeRows()
        assertEquals(
            listOf("0:A", "1:B", "2:2.pdf", "1:1.pdf", "1:z.pdf"),
            rows.map { "${it.depth}:${it.name}" },
        )
        assertTrue(rows.first().isFolder)
        assertFalse(rows.last().isFolder)
    }

    @Test
    fun `an empty folder appears in the tree`() {
        val rows = buildUploadPlan(emptyList(), setOf("Root/Empty")).treeRows()
        assertEquals(listOf("Root", "Empty"), rows.map { it.name })
    }

    // -- the batch -----------------------------------------------------------------------------

    @Test
    fun `a batch keeps one id and lowers its total as items fall away`() {
        val batch = UploadBatch.create(10)
        val id = batch.id
        assertEquals(7, batch.drop(3))
        assertEquals(6, batch.drop())
        assertEquals(id, batch.id)
        assertEquals(6, batch.total)
    }

    @Test
    fun `a total never goes below zero`() {
        assertEquals(0, UploadBatch.create(2).drop(5))
        assertEquals(0, UploadBatch.create(-3).total)
    }

    @Test
    fun `two actions never share a batch`() {
        assertNotEquals(UploadBatch.create(1).id, UploadBatch.create(1).id)
    }
}
