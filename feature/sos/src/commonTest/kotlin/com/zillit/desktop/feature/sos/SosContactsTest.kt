package com.zillit.desktop.feature.sos

import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosCrewMember
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosConfirm
import com.zillit.desktop.feature.sos.ui.SosContactTab
import com.zillit.desktop.feature.sos.ui.SosEvent
import com.zillit.desktop.feature.sos.ui.SosViewModel
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
import kotlin.test.assertTrue

/** The receiver list: whose list is asked for, adding, editing and removing on both tabs. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosContactsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = listOf(
        SosCrewMember(userId = "u-me", fullName = "Me Myself", designation = "Producer"),
        SosCrewMember(userId = "u-sam", fullName = "Sam Carter", designation = "Gaffer"),
        SosCrewMember(userId = "u-ada", fullName = "Ada Rees", designation = "Grip"),
    )

    private fun viewModel(
        repository: FakeSosRepository,
        admin: Boolean = false,
        phone: String = "7700900000",
    ) = SosViewModel(
        repository = repository,
        nowMillis = { 1_000L },
        viewer = { SosViewer(userId = "u-me", isAdmin = admin, phone = phone) },
        crew = { crew },
    )

    @Test
    fun `an admin asks for the admin list, a member for their own`() = runTest(dispatcher) {
        val asMember = FakeSosRepository()
        viewModel(asMember).start()
        advanceUntilIdle()
        assertEquals(listOf("user"), asMember.entryTypesAsked)

        val asAdmin = FakeSosRepository()
        viewModel(asAdmin, admin = true).start()
        advanceUntilIdle()
        assertEquals(listOf("admin"), asAdmin.entryTypesAsked)
    }

    @Test
    fun `the picker offers crew who are neither the viewer nor already a receiver`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            contactRows += contact(id = "c1", kind = SosContactKind.Internal, userId = "u-ada")
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        val state = viewModel.currentState
        assertEquals(listOf("u-sam"), state.contacts.addableCrew(state.viewer.userId).map { it.userId })

        viewModel.onEvent(SosEvent.CrewSearchChanged("zzz"))
        val narrowed = viewModel.currentState
        assertTrue(narrowed.contacts.addableCrew(narrowed.viewer.userId).isEmpty())
    }

    @Test
    fun `adding a member posts the user id and re-reads the list`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.SubmitMember("u-sam"))
        advanceUntilIdle()

        assertEquals(listOf("u-sam"), repository.created)
        assertEquals(listOf("u-sam"), viewModel.currentState.contacts.members.map { it.userId })
        assertEquals(2, repository.entryTypesAsked.size)
    }

    @Test
    fun `editing a member repoints the row rather than adding another`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            contactRows += contact(id = "c1", kind = SosContactKind.Internal, userId = "u-ada")
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.EditContact("c1"))
        assertEquals("c1", viewModel.currentState.contacts.editingId)
        assertEquals(SosContactTab.Member, viewModel.currentState.contacts.tab)

        viewModel.onEvent(SosEvent.SubmitMember("u-sam"))
        advanceUntilIdle()

        assertEquals(listOf("c1" to "u-sam"), repository.updated)
        assertTrue(repository.created.isEmpty())
        assertFalse(viewModel.currentState.contacts.isEditing)
    }

    @Test
    fun `the outsider form refuses an incomplete draft before anything is sent`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.SelectContactTab(SosContactTab.Outsider))
        viewModel.onEvent(SosEvent.ContactNameChanged("Jo Blake"))
        viewModel.onEvent(SosEvent.SubmitOutsider)
        advanceUntilIdle()

        assertEquals("Pick a relationship.", viewModel.currentState.contacts.formError)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun `a complete outsider draft is posted and the form goes back to empty`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.SelectContactTab(SosContactTab.Outsider))
        viewModel.onEvent(SosEvent.ContactNameChanged("Jo Blake"))
        viewModel.onEvent(SosEvent.RelationChanged("Family"))
        viewModel.onEvent(SosEvent.CountryCodeChanged("+44"))
        viewModel.onEvent(SosEvent.PhoneChanged("7700900000"))
        viewModel.onEvent(SosEvent.SubmitOutsider)
        advanceUntilIdle()

        assertEquals(listOf("Jo Blake"), repository.created)
        assertEquals(listOf("Jo Blake"), viewModel.currentState.contacts.outsiders.map { it.contactName })
        assertEquals("", viewModel.currentState.contacts.draft.contactName)
    }

    @Test
    fun `editing an outsider fills the form from the row and saves over it`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            contactRows += contact(
                id = "c2",
                kind = SosContactKind.External,
                draft = ExternalContactDraft(
                    contactName = "Jo Blake",
                    relation = "Family",
                    countryCode = "+44",
                    phoneNumber = "7700900000",
                ),
            )
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.EditContact("c2"))
        val form = viewModel.currentState.contacts
        assertEquals(SosContactTab.Outsider, form.tab)
        assertEquals("Jo Blake", form.draft.contactName)
        assertEquals("+44", form.draft.countryCode)

        viewModel.onEvent(SosEvent.PhoneChanged("7700900111"))
        viewModel.onEvent(SosEvent.SubmitOutsider)
        advanceUntilIdle()

        assertEquals(listOf("c2" to "Jo Blake"), repository.updated)
        assertEquals("7700900111", viewModel.currentState.contacts.outsiders.single().phoneNumber)
    }

    @Test
    fun `removing a receiver asks first and re-reads the list`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            contactRows += contact(id = "c1", kind = SosContactKind.Internal, userId = "u-ada")
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.AskDeleteContact("c1"))
        assertEquals(SosConfirm.DeleteContact("c1"), viewModel.currentState.confirm)
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(listOf("c1"), repository.deletedContacts)
        assertTrue(viewModel.currentState.contacts.rows.isEmpty())
    }

    @Test
    fun `a viewer with no number on their profile cannot open the outsider form`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeSosRepository(), phone = "")
        viewModel.start()
        advanceUntilIdle()
        assertFalse(viewModel.currentState.canAddOutsider)
    }
}
