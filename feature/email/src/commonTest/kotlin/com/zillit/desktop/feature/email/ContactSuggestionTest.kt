package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.readContact
import com.zillit.desktop.feature.email.domain.ContactSource
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.completeRecipient
import com.zillit.desktop.feature.email.domain.currentRecipientToken
import com.zillit.desktop.feature.email.domain.suggestionsFor
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.RecipientField
import com.zillit.desktop.feature.email.domain.ComposeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Suggesting who a message goes to.
 *
 * Ranking matters more than matching here: someone typing three characters
 * wants the person whose name *starts* that way, not the twelve whose company
 * happens to contain it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactSuggestionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val book = listOf(
        EmailContact("aisha@prod.com", "Aisha Khan", ContactSource.Saved, "Camera"),
        EmailContact("ravi@prod.com", "Ravi Menon", ContactSource.ProjectUser, "Sound"),
        EmailContact("khalid@vendor.com", "Khalid Rahman", ContactSource.Saved, "Aisha Rentals"),
        EmailContact("post@studio.com", "", ContactSource.Saved),
    )

    private fun match(query: String, exclude: List<String> = emptyList()) =
        book.suggestionsFor(query, exclude).map { it.address }

    // -- matching ----------------------------------------------------------

    @Test
    fun `a name prefix matches, and comes first`() {
        // Khalid's company is "Aisha Rentals", so he matches too — on a weaker
        // rank. Searching a company or department is worth having; burying the
        // person you actually named is not.
        assertEquals("aisha@prod.com", match("Ais").first())
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(match("ais"), match("AIS"))
    }

    @Test
    fun `a surname matches, not just the first name`() {
        // "kha" should find Aisha Khan as well as Khalid.
        assertTrue(match("kha").contains("aisha@prod.com"))
    }

    @Test
    fun `an address prefix matches`() {
        assertEquals(listOf("post@studio.com"), match("post@"))
    }

    @Test
    fun `an empty query suggests nothing`() {
        // A dropdown that opens on focus with everyone in it is noise.
        assertTrue(match("").isEmpty())
        assertTrue(match("   ").isEmpty())
    }

    @Test
    fun `no match suggests nothing rather than everything`() {
        assertTrue(match("zzzz").isEmpty())
    }

    // -- ranking -----------------------------------------------------------

    @Test
    fun `a name prefix beats a word prefix`() {
        // Khalid Rahman starts with the term; Aisha Khan only has a word that does.
        assertEquals("khalid@vendor.com", match("kha").first())
    }

    @Test
    fun `a name match beats a subtitle match`() {
        // Khalid's company is "Aisha Rentals" — Aisha herself must come first.
        val ranked = match("aisha")

        assertEquals("aisha@prod.com", ranked.first())
        assertTrue(ranked.contains("khalid@vendor.com"), "the weaker match should still be offered")
    }

    @Test
    fun `a saved contact outranks crew on an equal match`() {
        val tie = listOf(
            EmailContact("a@x.com", "Sam Crew", ContactSource.ProjectUser),
            EmailContact("b@x.com", "Sam Saved", ContactSource.Saved),
        )

        assertEquals("b@x.com", tie.suggestionsFor("sam").first().address)
    }

    @Test
    fun `the source bias never promotes a weaker match`() {
        // A crew member whose name starts with the term must still beat a saved
        // contact who merely contains it.
        val mixed = listOf(
            EmailContact("saved@x.com", "Not Relevant", ContactSource.Saved, "sound department"),
            EmailContact("crew@x.com", "Sound Recordist", ContactSource.ProjectUser),
        )

        assertEquals("crew@x.com", mixed.suggestionsFor("sound").first().address)
    }

    // -- hygiene -----------------------------------------------------------

    @Test
    fun `someone already on the message is not offered again`() {
        assertFalse(match("Ais", exclude = listOf("aisha@prod.com")).contains("aisha@prod.com"))
    }

    @Test
    fun `exclusion reads the address out of a full header`() {
        // The field holds `Name <addr>`, not a bare address — which is what the
        // composer inserts when a suggestion is picked.
        val offered = match("Ais", exclude = listOf("Aisha Khan <aisha@prod.com>"))

        assertFalse(offered.contains("aisha@prod.com"))
    }

    @Test
    fun `the same address from two sources appears once`() {
        val duplicated = listOf(
            EmailContact("a@x.com", "Sam", ContactSource.Saved),
            EmailContact("a@x.com", "Sam", ContactSource.ProjectUser),
        )

        assertEquals(1, duplicated.suggestionsFor("sam").size)
    }

    @Test
    fun `the list is capped`() {
        val many = (1..50).map { EmailContact("user$it@x.com", "User $it") }

        assertEquals(8, many.suggestionsFor("user").size)
    }

    // -- the field -----------------------------------------------------------

    @Test
    fun `only the entry being typed is matched`() {
        // Matching the whole string would find nothing once a second recipient
        // is added.
        assertEquals("rav", "Aisha <a@b.com>, rav".currentRecipientToken())
        assertEquals("rav", "rav".currentRecipientToken())
        assertEquals("", "a@b.com, ".currentRecipientToken())
    }

    @Test
    fun `choosing a suggestion replaces only what was typed`() {
        val completed = "Aisha <aisha@prod.com>, rav"
            .completeRecipient(EmailContact("ravi@prod.com", "Ravi Menon"))

        assertEquals("Aisha <aisha@prod.com>, Ravi Menon <ravi@prod.com>, ", completed)
    }

    @Test
    fun `choosing the first suggestion replaces the whole field`() {
        val completed = "ais".completeRecipient(EmailContact("aisha@prod.com", "Aisha Khan"))

        assertEquals("Aisha Khan <aisha@prod.com>, ", completed)
    }

    @Test
    fun `a nameless contact is inserted as a bare address`() {
        assertEquals("post@studio.com, ", "po".completeRecipient(EmailContact("post@studio.com")))
    }

    // -- through the composer ------------------------------------------------

    @Test
    fun `suggestions appear for the focused field only`() = runTest {
        val server = FakeMailServer().apply { storedContacts = book }
        val composer = ComposeViewModel(Composing(server, server, server, server), ComposeMode.New, null, null)
        advanceUntilIdle()

        composer.onEvent(typingIn(RecipientField.To, "ais"))
        assertTrue(composer.state.value.suggestions.isEmpty(), "nothing is focused yet")

        composer.onEvent(ComposeEvent.FocusChanged(RecipientField.To))

        assertEquals("aisha@prod.com", composer.state.value.suggestions.first().address)
    }

    @Test
    fun `picking a suggestion fills the focused field`() = runTest {
        val server = FakeMailServer().apply { storedContacts = book }
        val composer = ComposeViewModel(Composing(server, server, server, server), ComposeMode.New, null, null)
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.FocusChanged(RecipientField.Cc))
        composer.onEvent(typingIn(RecipientField.Cc, "ais"))

        composer.onEvent(ComposeEvent.ContactPicked(book.first()))

        assertEquals(listOf("aisha@prod.com"), composer.state.value.cc)
        assertEquals("", composer.state.value.ccInput, "the typed fragment is replaced by the chip")
        assertTrue(composer.state.value.to.isEmpty(), "the wrong field was filled")
    }

    @Test
    fun `crew are suggested without waiting for the address book`() = runTest {
        // They come from the production context already in memory, so they work
        // offline and before the contacts request returns.
        val crew = listOf(EmailContact("ravi@prod.com", "Ravi Menon", ContactSource.ProjectUser))
        val server = FakeMailServer()
        val composer = ComposeViewModel(
            Composing(server, server, server, server, crew = { crew }),
            ComposeMode.New,
        )
        composer.onEvent(ComposeEvent.FocusChanged(RecipientField.To))
        composer.onEvent(typingIn(RecipientField.To, "rav"))

        assertEquals(listOf("ravi@prod.com"), composer.state.value.suggestions.map { it.address })
    }

    // -- reading -------------------------------------------------------------

    @Test
    fun `a contact reads from either name shape`() {
        val joined = readContact(
            Json.parseToJsonElement("""{"email_address":"a@b.com","contact_name":"Aisha Khan"}"""),
        )!!
        val split = readContact(
            Json.parseToJsonElement("""{"email_address":"a@b.com","first_name":"Aisha","last_name":"Khan"}"""),
        )!!

        assertEquals("Aisha Khan", joined.name)
        assertEquals("Aisha Khan", split.name)
    }

    @Test
    fun `a contact with no name is still usable`() {
        val row = readContact(Json.parseToJsonElement("""{"email_address":"post@studio.com"}"""))!!

        assertEquals("", row.name)
        assertEquals("post@studio.com", row.asRecipient)
    }

    @Test
    fun `a contact with no address is dropped`() {
        // It cannot be written to, and an unsendable suggestion is worse than none.
        assertNull(readContact(Json.parseToJsonElement("""{"contact_name":"Nobody"}""")))
    }

    @Test
    fun `a contact never prints its address`() {
        // A contact list is personal data and these end up in log files.
        val printed = EmailContact("aisha@prod.com", "Aisha Khan").toString()

        assertFalse(printed.contains("aisha@prod.com"))
        assertFalse(printed.contains("Aisha"))
    }
}
