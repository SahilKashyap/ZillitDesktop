package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.AddressSuggestion
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.addressSuggestions
import com.zillit.desktop.feature.documentdistribution.domain.sendableCrew
import com.zillit.desktop.feature.documentdistribution.domain.stripInvisibleChars
import com.zillit.desktop.feature.documentdistribution.ui.ContactEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ZL-21622 crew suggestions, ZL-21475 invisible characters, and the contact form's inline email check. */
class AddressSuggestionsTest {

    @Test
    fun `crew resolve to their mailbox first and hide who cannot be sent to`() {
        val crew = listOf(
            DocDistCrewMember(
                "u1",
                "Vivek",
                mailboxAddress = "vivek@zillit.com",
                email = "vivek@gmail.com",
                job = "Gaffer",
            ),
            DocDistCrewMember("u2", "Asha", email = "asha@studio.com"),
            DocDistCrewMember("u3", "Left", mailboxAddress = "left@zillit.com", status = "left"),
            DocDistCrewMember("u4", "Declined", mailboxAddress = "no@zillit.com", status = "rejected"),
            DocDistCrewMember("u5", "Pending", mailboxAddress = "p@zillit.com", status = "pending"),
            DocDistCrewMember("u6", "Nobody"),
            DocDistCrewMember("u7", "Twice", mailboxAddress = "VIVEK@zillit.com"),
        ).sendableCrew()

        assertEquals(listOf("vivek@zillit.com", "asha@studio.com"), crew.map { it.email })
        assertEquals(AddressSuggestion("vivek@zillit.com", "Vivek", "Gaffer", isCrew = true), crew.first())
    }

    @Test
    fun `a nameless contact never erases a crew member's name`() {
        val crew = listOf(AddressSuggestion("sunil@zillit.com", "Sunil Gautam", "Grip", isCrew = true))
        val contacts = listOf(
            Contact(email = "Sunil@zillit.com"),
            Contact(email = "vendor@hire.com", name = "Hire Co", jobTitle = "Lighting"),
        )

        val merged = addressSuggestions(contacts, crew)

        assertEquals(2, merged.size)
        assertEquals(AddressSuggestion("Sunil@zillit.com", "Sunil Gautam", "Grip", isCrew = true), merged[0])
        assertEquals(AddressSuggestion("vendor@hire.com", "Hire Co", "Lighting", isCrew = false), merged[1])
    }

    @Test
    fun `a contact's own name and department win when it has them`() {
        val crew = listOf(AddressSuggestion("a@zillit.com", "Crew Name", "Camera", isCrew = true))
        val merged = addressSuggestions(listOf(Contact("a@zillit.com", "Saved Name", "DIT")), crew).single()
        assertEquals(AddressSuggestion("a@zillit.com", "Saved Name", "DIT", isCrew = true), merged)
    }

    @Test
    fun `invisible characters are stripped but emoji joiners survive`() {
        assertEquals("Call sheet", stripInvisibleChars("Call​ sheet".replace(" ", "­ ")).replace("­", ""))
        assertEquals("Day 12 wrap", stripInvisibleChars("﻿Day 12⁠ wrap‎"))
        assertEquals("ab", stripInvisibleChars("a‍b"), "a stray joiner inside a word goes")
        val technologist = "👩‍💻"
        assertEquals(technologist, stripInvisibleChars(technologist))
        assertEquals(technologist, stripInvisibleChars("👩​‍💻"))
        assertEquals("plain", stripInvisibleChars("plain"))
    }

    @Test
    fun `the contact form's email problem is held back until the field is left`() {
        val contacts = listOf(Contact("taken@studio.com"), Contact("me@studio.com"))
        val typing = ContactEditorState(email = "bad@")

        assertNotNull(typing.emailProblem(contacts))
        assertNull(typing.shownEmailError(contacts), "quiet while filling in for the first time")
        assertNotNull(typing.copy(emailTouched = true).shownEmailError(contacts))

        assertNotNull(ContactEditorState(email = "").emailProblem(contacts), "required")
        assertNotNull(ContactEditorState(email = "Taken@studio.com").emailProblem(contacts), "a clash on add")
        assertNull(
            ContactEditorState(originalEmail = "me@studio.com", email = "ME@studio.com").emailProblem(contacts),
            "the contact being edited does not clash with itself",
        )
        assertNull(ContactEditorState(email = "new@studio.com").emailProblem(contacts))
    }
}
