package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.editRoomBody
import com.zillit.desktop.feature.chat.data.roomDetailFrom
import com.zillit.desktop.feature.chat.data.roomPictureBody
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.GroupDetail
import com.zillit.desktop.feature.chat.domain.GroupMember
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Group info panel: what `GET chat-room/{id}` is read as, who may do what
 * to the room (the web's `InfoSiderGroup` gating), and the view model's
 * rename / leave / delete / picture flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val detail = GroupDetail(
        id = "g1",
        name = "Night Shoot",
        ownedBy = "me",
        members = listOf(GroupMember("me", isAdmin = true), GroupMember("ann", isAdmin = false)),
    )

    // -- the wire ----------------------------------------------------------

    @Test
    fun `the room is read with its roster, admins and picture`() {
        val data = Json.parseToJsonElement(
            """{"chat_room":{"_id":"g1","room_name":"Night Shoot","owned_by":"me","is_system_defined":false,
              |"group_picture":{"thumbnail":"t.jpg","name":"p.jpg","media":"m.jpg","bucket":"b","region":"r"},
              |"members":[{"user_id":"me","chat_group_admin":true},{"user_id":"ann","chat_group_admin":false},
              |{"user_id":"gone","enabled":false},{"user_id":"ann"}]}}""".trimMargin(),
        )

        val room = assertNotNull(roomDetailFrom(data))

        assertEquals("Night Shoot", room.name)
        assertEquals(listOf("me", "ann"), room.members.map { it.userId }, "a left member and a repeat are not listed")
        assertTrue(room.isAdmin("me"))
        assertFalse(room.isAdmin("ann"))
        assertEquals("m.jpg", room.picture?.media)
        assertEquals("t.jpg", room.picture?.thumbnail)
    }

    @Test
    fun `a cleared picture reads as none`() {
        val data = Json.parseToJsonElement(
            """{"chat_room":{"_id":"g1","room_name":"X","group_picture":{"media":"","thumbnail":""},"members":[]}}""",
        )
        assertNull(roomDetailFrom(data)?.picture)
    }

    @Test
    fun `the rename body is the create body plus the room id and the whole roster`() {
        val expected = Json.parseToJsonElement(
            """{"room_name":"Day Shoot","is_random_call_group":false,"owned_by":"me",""" +
                """"members":["me","ann"],"chat_room_id":"g1"}""",
        )
        assertEquals(expected, editRoomBody(detail, "Day Shoot"))
    }

    @Test
    fun `the picture body carries the five storage keys, and blanks to clear`() {
        val set = roomPictureBody(ChatAttachment("m.jpg", "p.jpg", "image/jpeg", "b", "r", "t.jpg"))
        assertEquals(
            Json.parseToJsonElement(
                """{"thumbnail":"t.jpg","name":"p.jpg","media":"m.jpg","bucket":"b","region":"r"}""",
            ),
            set,
        )
        // No poster key: the object itself stands in, so other clients have something to fetch.
        assertEquals(
            "m.jpg",
            (roomPictureBody(ChatAttachment("m.jpg", "p.jpg"))["thumbnail"] as kotlinx.serialization.json.JsonPrimitive)
                .content,
        )
        assertEquals(
            Json.parseToJsonElement("""{"thumbnail":"","name":"","media":"","bucket":"","region":""}"""),
            roomPictureBody(null),
        )
    }

    // -- who may do what -----------------------------------------------------

    @Test
    fun `an admin manages, any member re-pictures, a stranger does neither`() {
        assertTrue(detail.canManage("me"))
        assertFalse(detail.canManage("ann"))
        assertTrue(detail.canChangePicture("ann"))
        assertFalse(detail.canChangePicture("stranger"))
        assertFalse(detail.canManage(null))
    }

    @Test
    fun `the only admin cannot leave, a second admin or a plain member can`() {
        assertFalse(detail.canLeave("me"), "the room would be left with nobody to run it")
        assertTrue(detail.canLeave("ann"))
        val twoAdmins = detail.copy(members = detail.members.map { it.copy(isAdmin = true) })
        assertTrue(twoAdmins.canLeave("me"))
        assertFalse(detail.canLeave("stranger"), "only a member leaves")
    }

    @Test
    fun `a system room offers nothing`() {
        val system = detail.copy(isSystemDefined = true)
        assertFalse(system.canManage("me"))
        assertFalse(system.canLeave("ann"))
        assertFalse(system.canChangePicture("ann"))
    }

    @Test
    fun `members list administrators first, then by name`() {
        val room = detail.copy(
            members = listOf(GroupMember("z", false), GroupMember("a", false), GroupMember("m", true)),
        )
        val names = mapOf("z" to "Zed", "a" to "Amy", "m" to "Moe")
        assertEquals(listOf("m", "a", "z"), room.membersInOrder { names.getValue(it) }.map { it.userId })
    }

    // -- the view model --------------------------------------------------------

    private class GroupFake(val base: FakeChatRepository = FakeChatRepository()) : ChatRepository by base {
        var room: GroupDetail? = null
        var refuse: ZillitError? = null
        val renamed = mutableListOf<String>()
        val left = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val pictures = mutableListOf<ChatAttachment?>()

        private fun <T> answer(value: T): ZillitResult<T> =
            refuse?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(value)

        override suspend fun rooms(): ZillitResult<List<GroupRoom>> =
            ZillitResult.Success(room?.let { listOf(GroupRoom(it.id, it.name, it.ownedBy)) }.orEmpty())

        override suspend fun roomDetail(roomId: String): ZillitResult<GroupDetail> =
            room?.let { ZillitResult.Success(it) } ?: ZillitResult.Failure(ZillitError.Unknown("none"))

        override suspend fun renameRoom(room: GroupDetail, name: String): ZillitResult<Unit> {
            renamed += name
            return answer(Unit)
        }

        override suspend fun leaveRoom(roomId: String): ZillitResult<Unit> {
            left += roomId
            return answer(Unit)
        }

        override suspend fun deleteRoom(roomId: String): ZillitResult<Unit> {
            deleted += roomId
            return answer(Unit)
        }

        override suspend fun setRoomPicture(roomId: String, picture: ChatAttachment?): ZillitResult<Unit> {
            pictures += picture
            return answer(Unit)
        }
    }

    private fun opened(repository: GroupFake): ChatViewModel {
        val model = ChatViewModel(repository = repository, nowMillis = { 1L }, newUniqueId = { "u" })
        model.onEvent(ChatEvent.OpenGroup(GroupRoom("g1", "Night Shoot", "me")))
        model.onEvent(ChatEvent.LoadGroupInfo("g1"))
        return model
    }

    @Test
    fun `opening the panel loads the room`() = runTest(dispatcher) {
        val repository = GroupFake().apply { room = detail }
        val model = opened(repository)
        advanceUntilIdle()

        val info = assertNotNull(model.currentState.groupInfo)
        assertEquals(detail, info.detail)
        assertFalse(info.loading)
    }

    @Test
    fun `renaming sends the trimmed name and renames the listing and the open thread`() = runTest(dispatcher) {
        val repository = GroupFake().apply { room = detail }
        val model = opened(repository)
        advanceUntilIdle()

        model.onEvent(ChatEvent.RenameGroup("  Day Shoot "))
        advanceUntilIdle()

        assertEquals(listOf("Day Shoot"), repository.renamed)
        assertEquals("Day Shoot", model.currentState.groupInfo?.detail?.name)
        assertEquals("Day Shoot", model.currentState.peer?.fullName)
        assertNull(model.currentState.groupInfo?.error)
    }

    @Test
    fun `a name that is too short is refused before any call`() = runTest(dispatcher) {
        val repository = GroupFake().apply { room = detail }
        val model = opened(repository)
        advanceUntilIdle()

        model.onEvent(ChatEvent.RenameGroup("ab"))
        advanceUntilIdle()

        assertTrue(repository.renamed.isEmpty())
        assertNotNull(model.currentState.groupInfo?.error)
    }

    @Test
    fun `leaving closes the thread and takes the room off the list`() = runTest(dispatcher) {
        val repository = GroupFake().apply { room = detail }
        val model = opened(repository)
        advanceUntilIdle()

        model.onEvent(ChatEvent.LeaveGroup)
        advanceUntilIdle()

        assertEquals(listOf("g1"), repository.left)
        assertNull(model.currentState.peer)
        assertNull(model.currentState.groupInfo)
    }

    @Test
    fun `deleting calls delete-room and closes the thread`() = runTest(dispatcher) {
        val repository = GroupFake().apply { room = detail }
        val model = opened(repository)
        advanceUntilIdle()

        model.onEvent(ChatEvent.DeleteGroup)
        advanceUntilIdle()

        assertEquals(listOf("g1"), repository.deleted)
        assertNull(model.currentState.peer)
    }

    @Test
    fun `a refused delete keeps the thread and says why`() = runTest(dispatcher) {
        val repository = GroupFake().apply {
            room = detail
            refuse = ZillitError.Validation("Only an admin can do that")
        }
        val model = opened(repository)
        advanceUntilIdle()

        model.onEvent(ChatEvent.DeleteGroup)
        advanceUntilIdle()

        assertNotNull(model.currentState.peer, "the room is still there")
        val info = assertNotNull(model.currentState.groupInfo)
        assertFalse(info.busy)
        assertNotNull(info.error)
    }
}
