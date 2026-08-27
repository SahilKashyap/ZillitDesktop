package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.ui.FileRequestState
import com.zillit.desktop.feature.drive.ui.FileRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the "Request files" panel moves, without a coroutine in sight. */
class FileRequestsTest {

    private fun request(id: String, link: String = "https://z/$id") =
        DriveFileRequest(id = id, title = "Stills", destinationFolderId = "f1", link = link)

    /** A request needs somewhere to land and something to call itself. */
    @Test
    fun `the form will not submit without a title`() {
        val blank = FileRequestState(folderId = "f1")
        val named = blank.copy(title = "Location stills")

        assertFalse(blank.canSubmit)
        assertTrue(named.canSubmit)
    }

    /** Nor while one is already in flight — a double click must not make two links. */
    @Test
    fun `it will not submit twice`() {
        val busy = FileRequestState(folderId = "f1", title = "Stills", submitting = true)

        assertFalse(busy.canSubmit)
    }

    /** Days on screen, milliseconds on the wire. */
    @Test
    fun `expiry is sent in milliseconds`() {
        val draft = FileRequests.draft(FileRequestState(folderId = "f1", title = "Stills", expiryDays = 7))

        assertEquals(7L * 24 * 60 * 60 * 1000, draft?.expiresInMillis)
        assertEquals("f1", draft?.destinationFolderId)
    }

    /** A form with no folder has nothing to ask for. */
    @Test
    fun `no folder means no draft`() {
        assertNull(FileRequests.draft(FileRequestState(title = "Stills")))
    }

    /** The title is trimmed, as every other body in this app is. */
    @Test
    fun `the title is trimmed`() {
        val draft = FileRequests.draft(FileRequestState(folderId = "f1", title = "  Stills  "))

        assertEquals("Stills", draft?.title)
    }

    /** After creating: the form empties and the new link leads the list. */
    @Test
    fun `a new request goes to the top and empties the form`() {
        val before = FileRequestState(
            folderId = "f1",
            title = "Stills",
            description = "by Friday",
            submitting = true,
            requests = listOf(request("old")),
        )

        val after = FileRequests.created(before, request("new"))

        assertEquals(listOf("new", "old"), after.requests.map { it.id })
        assertEquals("", after.title)
        assertEquals("", after.description)
        assertFalse(after.submitting)
        assertEquals("new", after.created?.id)
    }

    /**
     * Revoking keeps the row and drops the address.
     *
     * Someone out there still holds that link; a row that vanished would
     * leave nothing to explain why it stopped working.
     */
    @Test
    fun `a revoked request keeps its row and loses its link`() {
        val before = FileRequestState(folderId = "f1", requests = listOf(request("a"), request("b")))

        val after = FileRequests.revoked(before, "a")

        val revoked = after.requests.single { it.id == "a" }
        assertTrue(revoked.revoked)
        assertEquals("", revoked.link)
        assertFalse(after.requests.single { it.id == "b" }.revoked, "only the one asked for")
    }

    /** Opening names the folder the files will land in. */
    @Test
    fun `opening carries the folder`() {
        val state = FileRequests.loaded(
            FileRequestState(folderId = "f1", folderName = "Recce", loading = true),
            listOf(request("a")),
        )

        assertEquals("Recce", state.folderName)
        assertFalse(state.loading)
        assertTrue(state.open)
    }
}
