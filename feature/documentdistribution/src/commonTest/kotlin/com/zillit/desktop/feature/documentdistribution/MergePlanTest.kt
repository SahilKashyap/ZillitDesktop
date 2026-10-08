package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.MergePerson
import com.zillit.desktop.feature.documentdistribution.domain.MergeSource
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.buildMergePlan
import com.zillit.desktop.feature.documentdistribution.domain.filteredFor
import com.zillit.desktop.feature.documentdistribution.domain.groupedForMerge
import com.zillit.desktop.feature.documentdistribution.domain.isMergeable
import com.zillit.desktop.feature.documentdistribution.domain.mergeDirectory
import com.zillit.desktop.feature.documentdistribution.domain.mergedFileName
import com.zillit.desktop.feature.documentdistribution.domain.stampedRecipients
import com.zillit.desktop.feature.documentdistribution.domain.summary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The merge dialog's rules, ported from the web's `mergeJobs.js` and `MergePrintModal.jsx`. */
class MergePlanTest {

    private fun doc(name: String, type: String?) = LibraryDocument(id = name, name = name, contentType = type)

    private fun person(email: String, name: String = "", dept: String = "", crew: Boolean = false, id: String = "") =
        MergePerson(email = email, name = name, department = dept, userId = id, isCrew = crew)

    // -- what can be merged ---------------------------------------------------

    @Test
    fun `only a pdf can be merged`() {
        assertTrue(doc("a.pdf", "application/pdf").isMergeable())
        assertFalse(doc("a.jpg", "image/jpeg").isMergeable())
        assertFalse(doc("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").isMergeable())
    }

    @Test
    fun `a generic binary type defers to the file name`() {
        assertTrue(doc("script.PDF", "application/octet-stream").isMergeable())
        assertFalse(doc("script.docx", "application/octet-stream").isMergeable())
        assertTrue(doc("script.pdf", null).isMergeable())
    }

    @Test
    fun `a category word is not a mime type`() {
        // The web read `attachment.content_type` ("document") and rejected every real PDF.
        assertTrue(doc("a.pdf", "document").isMergeable())
    }

    @Test
    fun `a mime parameter does not hide a pdf`() {
        assertTrue(doc("a", "application/pdf; charset=binary").isMergeable())
    }

    // -- the plan ---------------------------------------------------------------

    private val me = person("me@x.com", "Me", crew = true, id = "u1")
    private val rory = person("rory@x.com", "Rory")
    private val ada = person("ada@x.com", "Ada")
    private val docs = listOf(doc("a.pdf", "application/pdf"), doc("b.pdf", "application/pdf"), doc("c.xlsx", null))

    @Test
    fun `a mixed selection merges the pdfs and names what it left out`() {
        val plan = buildMergePlan(docs, me, includeSelf = false, watermarkSelf = true, chosen = listOf(rory))
        assertEquals(listOf("a.pdf", "b.pdf"), plan.documents.map { it.name })
        assertEquals(listOf("c.xlsx"), plan.skipped.map { it.name })
    }

    @Test
    fun `nobody chosen builds nothing`() {
        val plan = buildMergePlan(docs, me, includeSelf = false, watermarkSelf = true, chosen = emptyList())
        assertFalse(plan.isReady)
        assertEquals("2 documents", plan.summary())
    }

    @Test
    fun `your stamped copy leads the recipients and one call does it all`() {
        val plan = buildMergePlan(docs, me, includeSelf = true, watermarkSelf = true, chosen = listOf(rory, ada))
        assertEquals(listOf("me@x.com", "rory@x.com", "ada@x.com"), plan.stamped.map { it.email })
        assertFalse(plan.plainCopy)
        assertFalse(plan.needsJoin)
        assertEquals(6, plan.copyCount)
        assertEquals(3, plan.recipientCount)
        assertEquals("2 documents x 3 recipients", plan.summary())
    }

    @Test
    fun `a clean copy of your own plus crew copies is two calls and a join`() {
        val plan = buildMergePlan(docs, me, includeSelf = true, watermarkSelf = false, chosen = listOf(rory))
        assertTrue(plan.plainCopy)
        assertEquals(listOf("rory@x.com"), plan.stamped.map { it.email })
        assertTrue(plan.needsJoin)
        assertEquals(4, plan.copyCount)
        assertEquals("2 documents x 2 recipients", plan.summary())
    }

    @Test
    fun `a clean copy alone is a plain merge`() {
        val plan = buildMergePlan(docs, me, includeSelf = true, watermarkSelf = false, chosen = emptyList())
        assertTrue(plan.isReady)
        assertTrue(plan.stamped.isEmpty())
        assertFalse(plan.needsJoin)
        assertEquals("2 documents x 1 recipient", plan.summary())
    }

    @Test
    fun `no pdfs in the selection is not ready even with people chosen`() {
        val plan = buildMergePlan(listOf(doc("c.xlsx", null)), me, true, true, listOf(rory))
        assertFalse(plan.isReady)
    }

    @Test
    fun `each recipient is stamped with their own name`() {
        val unnamed = person("x@y.com")
        val plan = buildMergePlan(docs, me, includeSelf = false, watermarkSelf = true, chosen = listOf(rory, unnamed))
        val sent = plan.stampedRecipients(WatermarkStyle())
        assertEquals("Rory", sent[0].watermarkText)
        // Unnamed: the address stands in rather than a blank stamp.
        assertEquals("x@y.com", sent[1].watermarkText)
    }

    @Test
    fun `a style that renders blank falls back to a stamp rather than none`() {
        val plan = buildMergePlan(docs, me, false, true, listOf(rory))
        val blank = WatermarkStyle(line1 = com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine.None)
        assertEquals("CONFIDENTIAL", plan.stampedRecipients(blank).single().watermarkText)
    }

    // -- the file name -----------------------------------------------------------

    @Test
    fun `the file name carries the folder and the date`() {
        assertEquals("Week 1-merged-2026-10-07.pdf", mergedFileName(false, "Week 1", "2026-10-07"))
        assertEquals("Week 1-watermarked-2026-10-07.pdf", mergedFileName(true, "Week 1", "2026-10-07"))
        assertEquals("documents-merged-2026-10-07.pdf", mergedFileName(false, null, "2026-10-07"))
    }

    @Test
    fun `illegal characters never leave a dangling dash`() {
        assertEquals("A-B-C-merged-2026-10-07.pdf", mergedFileName(false, "A/B:C*?", "2026-10-07"))
        assertEquals("documents-merged-2026-10-07.pdf", mergedFileName(false, "///", "2026-10-07"))
    }

    // -- who can be picked --------------------------------------------------------

    private fun crewMember(id: String, name: String, mailbox: String, dept: String = "", status: String? = null) =
        DocDistCrewMember(userId = id, name = name, mailboxAddress = mailbox, department = dept, status = status)

    @Test
    fun `crew and contacts are one list, contacts winning on a clash`() {
        val directory = mergeDirectory(
            crew = listOf(crewMember("u1", "Me", "me@x.com"), crewMember("u2", "Rory", "rory@x.com", "Camera")),
            contacts = listOf(
                Contact("rory@x.com", name = "Rory B", jobTitle = "Grips"),
                Contact("vendor@y.com", name = "Vendor"),
            ),
            viewerUserId = "u1",
        )
        val rory = directory.people.single { it.email == "rory@x.com" }
        assertEquals("Rory B", rory.name)
        assertEquals("Grips", rory.department)
        assertTrue(rory.isCrew)
        assertEquals("u2", rory.userId)
        assertTrue(directory.people.single { it.email == "vendor@y.com" }.isContact)
    }

    @Test
    fun `an empty contact field never erases what the crew record knows`() {
        val directory = mergeDirectory(
            crew = listOf(crewMember("u2", "Rory", "rory@x.com", "Camera")),
            contacts = listOf(Contact("rory@x.com")),
            viewerUserId = "u1",
        )
        assertEquals("Rory", directory.people.single().name)
        assertEquals("Camera", directory.people.single().department)
    }

    @Test
    fun `you are not in the list, by id or by address`() {
        val directory = mergeDirectory(
            crew = listOf(crewMember("u1", "Me", "me@x.com"), crewMember("u2", "Rory", "rory@x.com")),
            // Your own contact entry carries no id; the address is what catches it.
            contacts = listOf(Contact("ME@x.com", name = "Me again")),
            viewerUserId = "u1",
        )
        assertEquals(listOf("rory@x.com"), directory.people.map { it.email })
        assertEquals("me@x.com", directory.me.email)
        assertEquals("Me", directory.me.name)
    }

    @Test
    fun `people who left or were never accepted are not offered`() {
        val directory = mergeDirectory(
            crew = listOf(
                crewMember("u2", "Gone", "gone@x.com", status = "left"),
                crewMember("u3", "Waiting", "wait@x.com", status = "pending"),
                crewMember("u4", "Here", "here@x.com", status = "accepted"),
            ),
            contacts = emptyList(),
            viewerUserId = "u1",
        )
        assertEquals(listOf("here@x.com"), directory.people.map { it.email })
    }

    @Test
    fun `the list is alphabetical by name, falling back to the address`() {
        val directory = mergeDirectory(
            crew = listOf(crewMember("u2", "Zed", "z@x.com"), crewMember("u3", "adam", "a@x.com")),
            contacts = listOf(Contact("m@x.com")),
            viewerUserId = "u1",
        )
        assertEquals(listOf("adam", "m@x.com", "Zed"), directory.people.map { it.displayName })
    }

    @Test
    fun `the viewer is still shown when they are not on the crew list`() {
        val directory = mergeDirectory(emptyList(), emptyList(), viewerUserId = "u1", viewerEmail = "me@home.com")
        assertEquals("me@home.com", directory.me.email)
    }

    // -- filtering and grouping ------------------------------------------------------

    private val people = listOf(
        person("cam@x.com", "Cam", dept = "Camera", crew = true, id = "u2"),
        person("grip@x.com", "Grip", dept = "Grips", crew = true, id = "u3"),
        person("nodept@x.com", "Nodept", crew = true, id = "u4"),
        person("vendor@y.com", "Vendor", dept = "Hire"),
    )

    @Test
    fun `the source filter separates crew from contacts`() {
        assertEquals(3, people.filteredFor(MergeSource.Crew, "").size)
        assertEquals(listOf("vendor@y.com"), people.filteredFor(MergeSource.Contacts, "").map { it.email })
        assertEquals(4, people.filteredFor(MergeSource.All, "").size)
    }

    @Test
    fun `search matches name, address, role and department`() {
        assertEquals(listOf("cam@x.com"), people.filteredFor(MergeSource.All, "camera").map { it.email })
        assertEquals(listOf("grip@x.com"), people.filteredFor(MergeSource.All, "  GRIP@x ").map { it.email })
    }

    @Test
    fun `departments run alphabetically, then other, then contacts`() {
        val groups = people.groupedForMerge("Other", "Contacts")
        assertEquals(listOf("Camera", "Grips", "Other", "Contacts"), groups.map { it.department })
        assertTrue(groups.last().isContacts)
        // A contact's department is a label for searching, never a heading of its own.
        assertEquals(listOf("vendor@y.com"), groups.last().members.map { it.email })
    }
}
