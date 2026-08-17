package com.zillit.desktop.core.session

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading a production's details.
 *
 * The shape below is what QA actually returns. `storage_folders` in particular
 * is an *object* wrapping an `entries` array, and typing it as a list made the
 * whole decode fail — taking the production's name and code down with it, for
 * the sake of a field only attachments need.
 */
class ProjectDetailTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) =
        json.decodeFromString(ProjectDetailDto.serializer(), body).toSnapshot("p1")

    @Test
    fun `the wrapper object shape QA sends is read`() {
        val snapshot = parse(
            """{"project_name":"Call testing","project_code":"984947","storage_type":"S3",
               "storage_folders":{"id":"","name":"","type":"","entries":[]}}""",
        )

        assertEquals("Call testing", snapshot.name)
        assertEquals("S3", snapshot.storageType)
        assertTrue(snapshot.storageFolders.isEmpty())
    }

    @Test
    fun `folders are read out of the nested entries`() {
        val snapshot = parse(
            """{"project_name":"P","storage_type":"BOX","enterprise_client_id":"ent-9",
               "storage_folders":{"entries":[{"name":"chat","id":"12"},{"name":"home","id":"34"}]}}""",
        )

        assertEquals(mapOf("chat" to "12", "home" to "34"), snapshot.storageFolders)
        assertEquals("ent-9", snapshot.enterpriseClientId)
    }

    @Test
    fun `a bare array is accepted too`() {
        // Only one of the two shapes is documented and neither is guaranteed.
        val snapshot = parse(
            """{"project_name":"P","storage_folders":[{"name":"chat","id":"12"}]}""",
        )

        assertEquals(mapOf("chat" to "12"), snapshot.storageFolders)
    }

    @Test
    fun `an unreadable storage_folders costs the folders, not the production`() {
        // The whole point: a field only attachments need must not be able to
        // take the production's name with it.
        val snapshot = parse("""{"project_name":"Still here","storage_folders":"nonsense"}""")

        assertEquals("Still here", snapshot.name)
        assertTrue(snapshot.storageFolders.isEmpty())
    }

    @Test
    fun `entries missing an id or a name are skipped`() {
        val snapshot = parse(
            """{"project_name":"P","storage_folders":{"entries":[
               {"name":"chat","id":"12"},{"name":"","id":"34"},{"name":"home"}]}}""",
        )

        assertEquals(mapOf("chat" to "12"), snapshot.storageFolders)
    }

    @Test
    fun `a production with no storage fields still reads`() {
        val snapshot = parse("""{"project_name":"P","project_code":"1"}""")

        assertEquals("P", snapshot.name)
        assertEquals(null, snapshot.storageType)
        assertTrue(snapshot.storageFolders.isEmpty())
    }
}
