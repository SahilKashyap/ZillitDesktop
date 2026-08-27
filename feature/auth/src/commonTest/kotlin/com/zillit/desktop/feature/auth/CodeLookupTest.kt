package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.toCodeLookup
import com.zillit.desktop.feature.auth.domain.CodeLookup
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Reading what a production code resolved to.
 *
 * `PUT user/join-project-as-user` answers with a `project` for a production's
 * shared code and a `user` for one issued to a person, and the flow forks on
 * which. Getting the fork wrong is not a visible error: it walks a
 * pre-approved crew member through a form they should never see, and files a
 * second request against their own membership.
 *
 * The bodies here are the shapes Android's `ProjectJoinedResponse` decodes.
 */
class CodeLookupTest {

    private fun read(body: String) = Json.parseToJsonElement(body).toCodeLookup()

    @Test
    fun `a production's own code asks for details next`() {
        val lookup = read(
            """
            {
              "project": {
                "project_id": "p1",
                "project_name": "Feature Film",
                "project_code": "986381",
                "project_type": "Feature",
                "project_type_id": "t1",
                "project_region": "IN"
              }
            }
            """.trimIndent(),
        )

        val needsDetails = assertIs<CodeLookup.NeedsDetails>(lookup)
        assertEquals("p1", needsDetails.project.id)
        assertEquals("Feature Film", needsDetails.project.name)
        assertEquals("986381", needsDetails.project.code)
        // The filterable id, never the localised label.
        assertEquals("t1", needsDetails.project.type)
    }

    @Test
    fun `a code issued to a person says they are already on it`() {
        val lookup = read(
            """
            {
              "user": {
                "_id": "row1",
                "user_id": "u1",
                "project_id": "p1",
                "first_name": "Peach",
                "status": "accepted"
              }
            }
            """.trimIndent(),
        )

        assertEquals(CodeLookup.AlreadyOn(projectId = "p1", userId = "u1"), lookup)
    }

    @Test
    fun `the record's own id stands in when there is no project user id`() {
        // Android reads `user_id` first and falls back to `_id`.
        val lookup = read("""{"user":{"_id":"row1","project_id":"p1"}}""")

        assertEquals(CodeLookup.AlreadyOn(projectId = "p1", userId = "row1"), lookup)
    }

    @Test
    fun `a project wins when the answer somehow carries both`() {
        val lookup = read(
            """{"project":{"project_id":"p1"},"user":{"user_id":"u1","project_id":"p2"}}""",
        )

        assertIs<CodeLookup.NeedsDetails>(lookup)
    }

    @Test
    fun `a user with no production is not something to act on`() {
        // Android sends these back to the production list rather than onward.
        assertNull(read("""{"user":{"user_id":"u1"}}"""))
    }

    @Test
    fun `an answer naming neither is refused`() {
        assertNull(read("{}"))
        assertNull(read("""{"project":{},"user":{}}"""))
        assertNull(read("[]"))
    }

    @Test
    fun `a project row with no id is not a production`() {
        assertNull(read("""{"project":{"project_name":"Feature Film"}}"""))
    }

    @Test
    fun `the underscore id stands in for a project too`() {
        val lookup = read("""{"project":{"_id":"p1","name":"Feature Film","code":"986381"}}""")

        val needsDetails = assertIs<CodeLookup.NeedsDetails>(lookup)
        assertEquals("p1", needsDetails.project.id)
        assertEquals("Feature Film", needsDetails.project.name)
        assertEquals("986381", needsDetails.project.code)
    }
}
