package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
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
import kotlin.test.assertNull

/**
 * Every person in the cash tool is a user id on the wire, and the screens
 * printed that id wherever the row carried no name — which was most rows.
 * These pin the web's lookup (`getUserName(user_id)` against the user list)
 * and the one place this client parts from it: an id is never the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashPeopleTest {

    private val ada = "6a2beb3023a3156e75c3ef85"
    private val grace = "6a3c7609ad621f8c3c0717df"
    private val people = CashPeople(
        listOf(
            AssigneeOption(userId = ada, fullName = "Ada Lovelace", designation = "Production Accountant"),
            AssigneeOption(userId = grace, fullName = "Grace Hopper"),
        ),
    )

    @Test
    fun `the crew list names an id`() {
        assertEquals("Ada Lovelace", people.nameOf(ada))
    }

    @Test
    fun `the crew list wins over a name the row carried, as on the web`() {
        assertEquals("Ada Lovelace", people.nameOf(ada, recorded = "A. Lovelace (old)"))
    }

    @Test
    fun `a row's own name serves for someone the crew list does not know`() {
        assertEquals("Hedy Lamarr", people.nameOf("6a2beccf0a26d21c6601e999", recorded = "Hedy Lamarr"))
    }

    /** A top-up's `holder_name` is read as an id by the web when `user_id` is missing. */
    @Test
    fun `an id in the name field is looked up rather than printed`() {
        assertEquals("Grace Hopper", people.nameOf(userId = "", recorded = grace))
    }

    @Test
    fun `an id nobody can name is never shown`() {
        val stranger = "6a2beb2f23a3156e75c3e85e"
        assertNull(people.nameOrNull(stranger))
        assertEquals(CashPeople.UNKNOWN, people.nameOf(stranger))
        assertEquals(CashPeople.UNKNOWN, people.nameOf(stranger, recorded = stranger))
        assertEquals(CashPeople.UNKNOWN, people.nameOf(null, recorded = stranger))
        assertEquals(CashPeople.UNKNOWN, people.nameOf(null, recorded = "81e71e48-b4cb-403c-a107-19efea93979a"))
    }

    @Test
    fun `nothing at all is unknown`() {
        assertEquals(CashPeople.UNKNOWN, CashPeople().nameOf(null))
        assertEquals(CashPeople.UNKNOWN, people.nameOf("", recorded = "  "))
    }

    @Test
    fun `a crew row with no name falls through to the row's own`() {
        val blank = CashPeople(listOf(AssigneeOption(userId = ada, fullName = " ")))
        assertEquals("Ada L.", blank.nameOf(ada, recorded = "Ada L."))
        assertEquals(CashPeople.UNKNOWN, blank.nameOf(ada))
    }

    // -- the view model keeps the list current ------------------------------

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The crew belongs to the production, whose user list can land after the
     * tool opens. Read once, those names would never arrive; every page load
     * reads it again.
     */
    @Test
    fun `a crew list that arrives after the tool opened still names people`() = runTest(dispatcher) {
        var crew = emptyList<AssigneeOption>()
        // A coordinator on a production that codes: the coding queue is theirs
        // to open, where a deep link to it would bounce anyone else.
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isCoordinator = true, codingRequired = true)
        }
        val vm = CashExpensesViewModel(
            repository = repository,
            viewer = { CashViewer(userId = ada, departmentIdentifier = "camera", designationIdentifier = null) },
            assignees = { crew },
        )
        vm.start()
        advanceUntilIdle()
        assertEquals(emptyList<AssigneeOption>(), vm.state.value.assignees)

        // The fake's batch carries "Sam Grip" itself; the crew list's spelling
        // showing instead is what proves the list was read.
        crew = listOf(AssigneeOption(userId = "u2", fullName = "Samuel Grip"))
        vm.onEvent(CashEvent.Open(CashDestination.CodingQueue))
        advanceUntilIdle()

        assertEquals(crew, vm.state.value.assignees)
        val batch = vm.state.value.queueBatches.single()
        assertEquals("Samuel Grip", CashPeople(vm.state.value.assignees).nameOf(batch.userId, batch.holderName))
    }
}
