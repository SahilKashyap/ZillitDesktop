package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.feature.chat.data.createRoomBody
import com.zillit.desktop.feature.chat.data.createdRoomFrom
import com.zillit.desktop.feature.chat.data.refuseStatusZero
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.GroupEditorEvent
import com.zillit.desktop.feature.chat.ui.GroupEditorViewModel
import com.zillit.desktop.feature.chat.ui.groupComplaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group creation (QA#13): the `POST chat-room` body Android sends, the
 * answer's parsing, the status-0 refusal, and the dialog's validation —
 * Android's refusals word for word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the wire ----------------------------------------------------------

    /**
     * Field for field Android's `ReqGroupModel` as `CreateGroupPage.kt:393-400`
     * fills it for a CNC group: `owned_by`, `room_tool` "cnc_section", the
     * name, the member ids — and nothing else, because Android's `Json`
     * omits the null-defaulted fields.
     */
    @Test
    fun `the create body matches Android's, exactly`() {
        val body = createRoomBody("Night Shoot", "u-me", listOf("u1", "u2"))

        val expected: JsonElement = Json.parseToJsonElement(
            """{"room_name":"Night Shoot","room_tool":"cnc_section",""" +
                """"owned_by":"u-me","members":["u1","u2"]}""",
        )
        assertEquals(expected, body)
    }

    @Test
    fun `the created room is read out of data-chat_room`() {
        val data = Json.parseToJsonElement(
            """{"chat_room":{"_id":"g-new","room_name":"Night Shoot","sorting_activity":0}}""",
        )

        val room = createdRoomFrom(data)

        assertEquals("g-new", room?.id)
        assertEquals("Night Shoot", room?.name)
        assertNull(room?.departmentId)
    }

    @Test
    fun `a status-0 answer is a refusal wearing 200`() {
        val refused = ZillitResult.Success(ApiEnvelope(status = 0, message = "cnc_room_tool_validation"))
            .refuseStatusZero()
        val passed = ZillitResult.Success(ApiEnvelope(status = 1)).refuseStatusZero()

        assertTrue(refused is ZillitResult.Failure)
        assertEquals(
            "cnc_room_tool_validation",
            (refused.error as ZillitError.Validation).userMessage,
        )
        assertTrue(passed is ZillitResult.Success)
    }

    // -- validation, Android's words ---------------------------------------

    @Test
    fun `validation speaks Android's refusals in Android's order`() {
        assertEquals("Group name is required.", groupComplaint("   ", setOf("u1")))
        assertEquals(
            "Please Enter Group Name of length at least 3 characters or at most 40 characters.",
            groupComplaint("ab", setOf("u1")),
        )
        assertEquals(
            "Please Enter Group Name of length at least 3 characters or at most 40 characters.",
            groupComplaint("x".repeat(41), setOf("u1")),
        )
        assertEquals("Group members are required.", groupComplaint("Night Shoot", emptySet()))
        assertNull(groupComplaint("Night Shoot", setOf("u1")))
    }

    // -- the view model ----------------------------------------------------

    @Test
    fun `an invalid draft never reaches the wire`() = runTest(dispatcher) {
        var called = false
        val model = GroupEditorViewModel(
            createRoom = { _, _ ->
                called = true
                ZillitResult.Success(GroupRoom("g", "g"))
            },
            onCreated = {},
        )

        model.onEvent(GroupEditorEvent.NameChanged("ab"))
        model.onEvent(GroupEditorEvent.ToggleMember("u1"))
        model.onEvent(GroupEditorEvent.Create)
        advanceUntilIdle()

        assertFalse(called)
        assertEquals(
            "Please Enter Group Name of length at least 3 characters or at most 40 characters.",
            model.currentState.error,
        )
    }

    @Test
    fun `a valid draft creates with the trimmed name and the picked members`() =
        runTest(dispatcher) {
            var sentName: String? = null
            var sentMembers: List<String>? = null
            var created: GroupRoom? = null
            val model = GroupEditorViewModel(
                createRoom = { name, members ->
                    sentName = name
                    sentMembers = members
                    ZillitResult.Success(GroupRoom("g-new", name))
                },
                onCreated = { created = it },
            )

            model.onEvent(GroupEditorEvent.NameChanged("  Night Shoot  "))
            model.onEvent(GroupEditorEvent.ToggleMember("u1"))
            model.onEvent(GroupEditorEvent.ToggleMember("u2"))
            model.onEvent(GroupEditorEvent.ToggleMember("u2")) // and off again
            model.onEvent(GroupEditorEvent.Create)
            advanceUntilIdle()

            assertEquals("Night Shoot", sentName)
            assertEquals(listOf("u1"), sentMembers)
            assertEquals("g-new", created?.id, "success reaches the host's refresh")
            assertFalse(model.currentState.isBusy)
            assertNull(model.currentState.error)
        }

    @Test
    fun `a server refusal lands on the error line, not in onCreated`() = runTest(dispatcher) {
        var created = false
        val model = GroupEditorViewModel(
            createRoom = { _, _ ->
                ZillitResult.Failure(ZillitError.Validation("Group name already exists."))
            },
            onCreated = { created = true },
        )

        model.onEvent(GroupEditorEvent.NameChanged("Night Shoot"))
        model.onEvent(GroupEditorEvent.ToggleMember("u1"))
        model.onEvent(GroupEditorEvent.Create)
        advanceUntilIdle()

        assertFalse(created)
        assertFalse(model.currentState.isBusy)
        assertEquals("Group name already exists.", model.currentState.error)
    }

    @Test
    fun `Reset clears a dismissed draft for the next opening`() = runTest(dispatcher) {
        val model = GroupEditorViewModel(
            createRoom = { _, _ -> ZillitResult.Success(GroupRoom("g", "g")) },
            onCreated = {},
        )

        model.onEvent(GroupEditorEvent.NameChanged("Night Shoot"))
        model.onEvent(GroupEditorEvent.ToggleMember("u1"))
        model.onEvent(GroupEditorEvent.Reset)

        assertEquals("", model.currentState.name)
        assertTrue(model.currentState.selected.isEmpty())
    }
}
