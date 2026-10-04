package com.zillit.desktop.core.session

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The users list's per-viewer `sorting_activity` — when the signed-in user and
 * this person last exchanged a message. The chat list shows a DM by it, as
 * Android's does, so it must survive the parse in every shape it arrives in.
 */
class UserSortingActivityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun user(field: String) =
        json.decodeFromString(ProjectUserDto.serializer(), """{"user_id":"u1","name":"Aisha Khan"$field}""")
            .toSnapshot()!!

    @Test
    fun `a number is read as the stamp`() {
        assertEquals(1_786_507_000_000L, user(""","sorting_activity":1786507000000""").sortingActivity)
    }

    @Test
    fun `a numeric string is read too, so a loosely typed server cannot hide a conversation`() {
        assertEquals(1_786_507_000_000L, user(""","sorting_activity":"1786507000000"""").sortingActivity)
    }

    @Test
    fun `absent, null, zero or junk is no conversation`() {
        assertEquals(0L, user("").sortingActivity)
        assertEquals(0L, user(""","sorting_activity":null""").sortingActivity)
        assertEquals(0L, user(""","sorting_activity":0""").sortingActivity)
        assertEquals(0L, user(""","sorting_activity":"soon"""").sortingActivity)
    }
}
