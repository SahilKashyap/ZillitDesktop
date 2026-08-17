package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.FolderNameError
import com.zillit.desktop.feature.email.domain.isDeletable
import com.zillit.desktop.feature.email.domain.isRenameable
import com.zillit.desktop.feature.email.domain.validateFolderName
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import com.zillit.desktop.feature.email.ui.PendingConfirm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Creating and renaming folders.
 *
 * The rules are the interesting part. Two of them — the duplicate and the
 * reserved name — the client already knows, and a round trip to be told so is a
 * round trip wasted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderManagementTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val existing = listOf(
        EmailFolder("INBOX", isSystem = true),
        EmailFolder("Sent", isSystem = true),
        EmailFolder("Vendors"),
    )

    private fun check(name: String, renamingFrom: String? = null) =
        validateFolderName(name, existing, renamingFrom)

    // -- the rules ---------------------------------------------------------

    @Test
    fun `an ordinary name is accepted`() {
        assertNull(check("Location scouting"))
        assertNull(check("VFX — round 2"))
    }

    @Test
    fun `a blank name is refused`() {
        assertEquals(FolderNameError.Blank, check(""))
        assertEquals(FolderNameError.Blank, check("   "))
    }

    @Test
    fun `a duplicate is refused, whatever its case`() {
        assertEquals(FolderNameError.Duplicate, check("Vendors"))
        assertEquals(FolderNameError.Duplicate, check("vendors"))
        assertEquals(FolderNameError.Duplicate, check("  Vendors  "), "trimmed before comparing")
    }

    @Test
    fun `a folder does not collide with itself when renamed`() {
        // Renaming "Vendors" to "vendors" to fix its capitalisation must work.
        assertNull(check("vendors", renamingFrom = "Vendors"))
    }

    @Test
    fun `renaming onto another folder is still refused`() {
        assertEquals(FolderNameError.Duplicate, check("Vendors", renamingFrom = "Something else"))
    }

    @Test
    fun `the mail server's own names are reserved`() {
        // Even where the production has no such folder yet.
        listOf("INBOX", "inbox", "Sent", "Drafts", "Trash", "Junk", "Spam").forEach { name ->
            assertEquals(FolderNameError.Reserved, check(name), name)
        }
    }

    @Test
    fun `hierarchy delimiters are refused`() {
        // `/` and `.` are folder separators depending on the server, so a name
        // containing one either nests by accident or is rejected outright.
        listOf("Vendors/2024", "a.b", """back\slash""", "quote\"mark").forEach { name ->
            assertEquals(FolderNameError.IllegalCharacter, check(name), name)
        }
    }

    @Test
    fun `control characters are refused`() {
        assertEquals(FolderNameError.IllegalCharacter, check("line\nbreak"))
    }

    @Test
    fun `an absurdly long name is refused`() {
        assertEquals(FolderNameError.TooLong, check("a".repeat(200)))
    }

    @Test
    fun `system folders cannot be renamed`() {
        // The server addresses them by name, and every client looks for `INBOX`
        // by that exact string.
        assertFalse(EmailFolder("INBOX", isSystem = true).isRenameable)
        assertFalse(EmailFolder("Trash", isSystem = true).isRenameable)
        // Even if the server forgot to flag it.
        assertFalse(EmailFolder("Sent").isRenameable)

        assertTrue(EmailFolder("Vendors").isRenameable)
    }

    // -- through the mailbox -----------------------------------------------

    private fun mailbox(server: FakeMailServer) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { 1_000L },
    )

    private suspend fun loaded(server: FakeMailServer): EmailViewModel {
        val mailbox = mailbox(server)
        mailbox.onEvent(EmailEvent.Load)
        return mailbox
    }

    /** The dialog lives on the editor, beside the mailbox's own state. */
    private val EmailViewModel.dialog get() = folderEditor.state.value

    @Test
    fun `creating sends the name and reloads the sidebar`() = runTest {
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("Location scouting"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        assertEquals(listOf("Location scouting"), server.createdFolders)
        assertTrue(mailbox.state.value.folders.any { it.name == "Location scouting" })
        assertNull(mailbox.dialog, "the dialog should have closed")
    }

    @Test
    fun `the name is trimmed before it is sent`() = runTest {
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("  Scouting  "))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        assertEquals(listOf("Scouting"), server.createdFolders)
    }

    @Test
    fun `an invalid name never reaches the server`() = runTest {
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("INBOX"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        assertTrue(server.createdFolders.isEmpty())
        assertEquals(FolderNameError.Reserved, mailbox.dialog?.error)
    }

    @Test
    fun `the error clears as the name is corrected`() = runTest {
        // Leaving it up would keep a field red that the user has already fixed.
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("INBOX"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.FolderNameChanged("INBOX archive"))

        assertNull(mailbox.dialog?.error)
    }

    @Test
    fun `renaming opens with the current name filled in`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))

        val edit = mailbox.dialog
        assertEquals("Vendors", edit?.name)
        assertTrue(edit?.isRename == true)
    }

    @Test
    fun `renaming sends both names`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.FolderNameChanged("Suppliers"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        assertEquals(listOf("Vendors" to "Suppliers"), server.renamedFolders)
    }

    @Test
    fun `renaming the open folder follows it`() = runTest {
        // A renamed folder is a different folder to every mail call, so leaving
        // the selection behind would show an empty list under the old name.
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Vendors"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.FolderNameChanged("Suppliers"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        assertEquals("Suppliers", mailbox.state.value.selectedFolderName)
    }

    @Test
    fun `a server refusal keeps the dialog open with what was typed`() = runTest {
        // Losing a name to a network blip is a small thing done twice.
        val server = FakeMailServer().apply { folderWriteFails = true }
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("Scouting"))
        mailbox.onEvent(EmailEvent.SaveFolder)
        advanceUntilIdle()

        val edit = mailbox.dialog
        assertEquals("Scouting", edit?.name)
        assertFalse(edit?.isSaving == true, "it should not still look busy")
        assertEquals("already exists", mailbox.state.value.error)
    }

    @Test
    fun `dismissing throws the dialog away`() = runTest {
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder())
        mailbox.onEvent(EmailEvent.FolderNameChanged("Half typed"))
        mailbox.onEvent(EmailEvent.DismissFolderEdit)
        advanceUntilIdle()

        assertNull(mailbox.dialog)
        assertTrue(server.createdFolders.isEmpty())
    }

    // -- deleting ------------------------------------------------------------

    @Test
    fun `deleting asks first`() = runTest {
        // This is the only action in the module that can destroy mail the user
        // never chose to delete, because it takes the folder's contents.
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        assertIs<PendingConfirm.DeleteFolder>(mailbox.state.value.pendingConfirm)
        assertTrue(server.deletedFolders.isEmpty(), "it deleted before asking")
        assertNull(mailbox.dialog, "the edit dialog should have given way to the confirmation")
    }

    @Test
    fun `the confirmation says how much mail is at stake`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Vendors"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        val pending = assertIs<PendingConfirm.DeleteFolder>(mailbox.state.value.pendingConfirm)
        assertEquals(3, pending.cachedCount, "the count the user is shown")
    }

    @Test
    fun `confirming deletes and drops the folder from the sidebar`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertEquals(listOf("Vendors"), server.deletedFolders)
        assertFalse(mailbox.state.value.folders.any { it.name == "Vendors" })
    }

    @Test
    fun `a deleted folder's mail does not linger on disk`() = runTest {
        // Left behind it stays readable under a folder that no longer exists —
        // and comes back if someone recreates the name, since IMAP identifies
        // folders by name. Android deletes the folder row and leaks the mail.
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val cache = InMemoryMailboxCache()
        val mailbox = EmailViewModel(
            mailbox = Mailbox(server, cache),
            repository = server,
            draftRepository = server,
            folderEditor = FolderEditor(server),
            nowMillis = { 1_000L },
        )
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Vendors"))
        advanceUntilIdle()
        assertTrue(cache.messages("Vendors").isNotEmpty(), "precondition: mail was cached")

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertTrue(cache.messages("Vendors").isEmpty(), "the mail outlived its folder")
    }

    @Test
    fun `deleting the open folder moves off it`() = runTest {
        // Otherwise the list keeps showing a folder that is gone.
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Vendors"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertEquals("INBOX", mailbox.state.value.selectedFolder?.name)
    }

    @Test
    fun `cancelling deletes nothing`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.DismissConfirm)
        advanceUntilIdle()

        assertTrue(server.deletedFolders.isEmpty())
        assertTrue(mailbox.state.value.folders.any { it.name == "Vendors" })
    }

    @Test
    fun `a system folder cannot be deleted even if asked`() = runTest {
        // A mailbox without an Inbox is not a mailbox, and the server would
        // refuse anyway — but the request should never leave.
        val server = FakeMailServer()
        val mailbox = loaded(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("INBOX", isSystem = true)))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        assertNull(mailbox.state.value.pendingConfirm)
        assertTrue(server.deletedFolders.isEmpty())
        assertTrue(EmailFolder("INBOX", isSystem = true).isDeletable.not())
    }

    @Test
    fun `a refused delete leaves the folder alone and says why`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Vendors"))
            .apply { folderWriteFails = true }
        val mailbox = loaded(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.EditFolder(EmailFolder("Vendors")))
        mailbox.onEvent(EmailEvent.DeleteFolder)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertTrue(mailbox.state.value.folders.any { it.name == "Vendors" })
        assertEquals("in use", mailbox.state.value.error)
    }
}
