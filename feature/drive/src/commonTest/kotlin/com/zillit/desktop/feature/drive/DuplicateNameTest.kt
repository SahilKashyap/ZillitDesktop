package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.drive.domain.isDuplicateName
import com.zillit.desktop.feature.drive.domain.keepExtension
import com.zillit.desktop.feature.drive.domain.suggestFreeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateNameTest {

    private fun refused(message: String?) = ZillitError.Http(status = 200, serverMessage = message)

    @Test
    fun `the three keys the server uses for a taken name are recognised`() {
        assertTrue(refused("duplicate_file_name").isDuplicateName())
        assertTrue(refused("duplicate_folder_name").isDuplicateName())
        assertTrue(refused("duplicate_name").isDuplicateName())
    }

    @Test
    fun `a body the network layer left whole still names the key`() {
        assertTrue(refused("""{"status":0,"message":"duplicate_file_name","data":{}}""").isDuplicateName())
        assertTrue(refused("Duplicate_File_Name").isDuplicateName(), "case does not matter")
    }

    @Test
    fun `other failures are not taken for a duplicate`() {
        assertFalse(refused("route_not_found").isDuplicateName())
        assertFalse(refused(null).isDuplicateName())
        assertFalse(refused("duplicate invoice").isDuplicateName(), "only the three name keys")
        assertFalse(ZillitError.NoConnection().isDuplicateName())
        assertFalse(ZillitError.Forbidden().isDuplicateName())
    }

    @Test
    fun `a suggestion goes before the extension`() {
        assertEquals("report (1).pdf", suggestFreeName("report.pdf", emptySet()))
        assertEquals("notes (1)", suggestFreeName("notes", emptySet()), "no extension, nothing to keep")
        assertEquals("archive.tar (1).gz", suggestFreeName("archive.tar.gz", emptySet()))
        assertEquals(".env (1)", suggestFreeName(".env", emptySet()), "a leading dot is a name, not an extension")
    }

    @Test
    fun `a suggestion skips names that are taken, whatever their case`() {
        val taken = setOf("report.pdf", "Report (1).PDF", "report (2).pdf")

        assertEquals("report (3).pdf", suggestFreeName("report.pdf", taken))
    }

    @Test
    fun `a typed name keeps the file's extension`() {
        assertEquals("cut two.mov", keepExtension("cut two", "cut one.mov"))
        assertEquals("cut two.MOV", keepExtension("cut two.MOV", "cut one.mov"), "already ends in it")
        assertEquals("cut two.mov", keepExtension("  cut two  ", "cut one.mov"), "trimmed")
        assertEquals("notes v2", keepExtension("notes v2", "notes"), "nothing to keep")
        assertEquals("", keepExtension("   ", "a.txt"), "blank stays blank for the caller to refuse")
    }
}
