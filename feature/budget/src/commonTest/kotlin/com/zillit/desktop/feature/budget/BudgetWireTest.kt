package com.zillit.desktop.feature.budget

import com.zillit.desktop.feature.budget.data.activityRowsOf
import com.zillit.desktop.feature.budget.data.chatEntriesOf
import com.zillit.desktop.feature.budget.data.chatListBody
import com.zillit.desktop.feature.budget.data.createRoomBody
import com.zillit.desktop.feature.budget.data.createdGroupOf
import com.zillit.desktop.feature.budget.data.deleteBody
import com.zillit.desktop.feature.budget.data.documentsOf
import com.zillit.desktop.feature.budget.data.postBody
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the service says, and what it is told. */
class BudgetWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The list arrives under `budgets`, both types together, with the version fields. */
    @Test
    fun `the list reads every version field`() {
        val body = json.parseToJsonElement(
            """
            {"budgets":[
              {"_id":"b1","budget_type":"main","budget_title":"Budget (Full) -SEP 05, 2026","episode":3,
               "attachment":{"media":"k/main.pdf","name":"Main.pdf","size":2048,"thumbnail":"t/pdf.png"},
               "user_id":"u1","created":10,"updated":20,"user_visit":1,"deleted":0},
              {"_id":"b2","budget_type":"department","department_id":"d9","department_name":"Camera",
               "attachment":{"media":"k/cam.xlsx","name":"Camera.xlsx"},"full_name":"Ravi Menon","deleted":true}
            ]}
            """.trimIndent(),
        )

        val documents = documentsOf(body, json)

        assertEquals(2, documents.size)
        val main = documents[0]
        assertEquals(BudgetType.Main, main.type)
        assertEquals("Budget (Full) -SEP 05, 2026", main.title)
        assertEquals("3", main.episode, "a numeric episode reads as text")
        assertEquals("t/pdf.png", main.file?.thumbnail)
        assertEquals(20, main.updatedMillis)
        assertEquals("u1", main.uploadedById)
        assertFalse(main.deleted)
        assertTrue(documents[1].deleted)
        assertTrue(documents[1].file?.isSpreadsheet == true)
    }

    /** A deleted budget keeps its row with an empty attachment; nothing must offer to open it. */
    @Test
    fun `an emptied attachment is no file`() {
        val body = json.parseToJsonElement("""{"budgets":[{"_id":"b1","budget_type":"main","attachment":{}}]}""")

        assertNull(documentsOf(body, json).single().file)
    }

    /** The upload body: type, the web's attachment shape, the title, the episode only when set. */
    @Test
    fun `the upload body names the department only for a department budget`() {
        val file = BudgetFile(
            media = "k/a.pdf", name = "a.pdf", bucket = "b", region = "r", sizeBytes = 5, thumbnail = "t",
        )
        val department = postBody(
            BudgetUpload(BudgetType.Department, file, "Camera - SEP 05, 2026", departmentId = "d1", episode = "2"),
        )
        val main = postBody(BudgetUpload(BudgetType.Main, file, "Budget (Full) -SEP 05, 2026"))

        assertEquals("department", department["budget_type"]?.jsonPrimitive?.content)
        assertEquals("d1", department["department_id"]?.jsonPrimitive?.content)
        assertEquals("2", department["episode"]?.jsonPrimitive?.content)
        assertEquals("Camera - SEP 05, 2026", department["budget_title"]?.jsonPrimitive?.content)
        val attachment = department["attachment"]!!.jsonObject
        assertEquals("document", attachment["content_type"]?.jsonPrimitive?.content)
        assertEquals("pdf", attachment["content_subtype"]?.jsonPrimitive?.content)
        assertEquals("t", attachment["thumbnail"]?.jsonPrimitive?.content)
        assertNull(main["department_id"])
        assertNull(main["episode"])
    }

    @Test
    fun `the delete body is a list even for one`() {
        assertEquals(1, deleteBody(listOf("b1"))["budget_ids"]!!.jsonArray.size)
    }

    /** `getChatUserList`: the department rides only off the department tile. */
    @Test
    fun `the chat list ask adds the department on the department tile only`() {
        val main = chatListBody(BudgetMode.Main, "p1", "u1", "d1", "b1")
        val department = chatListBody(BudgetMode.Department, "p1", "u1", "d1", "b1")

        assertEquals("main_budget_tool", main["chat_tool"]?.jsonPrimitive?.content)
        assertNull(main["department_id"])
        assertEquals("d1", department["department_id"]?.jsonPrimitive?.content)
        assertEquals("b1", department["budget_document_id"]?.jsonPrimitive?.content)
    }

    /** `createGroup`: room tool per tile, and the document the server insists on. */
    @Test
    fun `the room body carries tool department owner members and document`() {
        val body = createRoomBody(BudgetMode.Department, "d1", "b1", "me", "Camera crew", listOf("u2", "u3"))

        assertEquals("department_budget_tool", body["room_tool"]?.jsonPrimitive?.content)
        assertEquals("d1", body["department_id"]?.jsonPrimitive?.content)
        assertEquals("me", body["owned_by"]?.jsonPrimitive?.content)
        assertEquals("b1", body["budget_document_id"]?.jsonPrimitive?.content)
        assertEquals(2, body["members"]!!.jsonArray.size)
    }

    /** `budget:recent:list` answers people and rooms in one array. */
    @Test
    fun `the recent list tells people from rooms`() {
        val ack = json.parseToJsonElement(
            """
            {"detail":[
              {"user_id":"u2","full_name":"Ravi Menon","designation_name":"gaffer_label","is_admin":true},
              {"_id":"r1","room_name":"Camera crew","is_group":true,"owned_by":"u1",
               "members":[{"user_id":"u1","enabled":true},{"user_id":"u2","enabled":false}],
               "group_picture":{"media":"k/pic.jpg"},"budget_document_id":"b1"}
            ]}
            """.trimIndent(),
        )

        val entries = chatEntriesOf(ack)

        val person = entries[0] as BudgetChatEntry.Person
        val group = entries[1] as BudgetChatEntry.Group
        assertEquals("u2", person.key)
        assertTrue(person.isAdmin)
        // A label key on the wire; the row carries the words the list prints.
        assertEquals("Gaffer", person.designation)
        assertEquals("r1", group.key)
        assertEquals(listOf("u1"), group.memberIds, "a disabled member is out of the room")
        assertEquals("k/pic.jpg", group.pictureMedia)
    }

    @Test
    fun `the created room comes back under chat_room`() {
        val data = json.parseToJsonElement("""{"chat_room":{"_id":"r9","room_name":"New","owned_by":"me"}}""")

        assertEquals("r9", createdGroupOf(data)?.roomId)
    }

    /** The count endpoint: `{user_id, view_count, download_count}` rows. */
    @Test
    fun `activity rows read both counts`() {
        val data = json.parseToJsonElement("""[{"user_id":"u1","view_count":3,"download_count":"1"}]""")

        val row = activityRowsOf(data).single()

        assertEquals(3, row.viewCount)
        assertEquals(1, row.downloadCount)
    }
}
