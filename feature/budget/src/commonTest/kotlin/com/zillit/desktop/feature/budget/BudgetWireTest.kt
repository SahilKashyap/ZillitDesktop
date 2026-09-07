package com.zillit.desktop.feature.budget

import com.zillit.desktop.feature.budget.data.deleteBody
import com.zillit.desktop.feature.budget.data.documentsOf
import com.zillit.desktop.feature.budget.data.postBody
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the service says, and what it is told. */
class BudgetWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The list arrives under `budgets`, both types together. */
    @Test
    fun `the list splits into main and department`() {
        val body = json.parseToJsonElement(
            """
            {"budgets":[
              {"_id":"b1","budget_type":"main","attachment":{"media":"k/main.pdf","name":"Main.pdf","size":2048}},
              {"_id":"b2","budget_type":"department","department_id":"d9","department_name":"Camera",
               "attachment":{"media":"k/cam.xlsx","name":"Camera.xlsx"},"full_name":"Ravi Menon"}
            ]}
            """.trimIndent(),
        )

        val documents = documentsOf(body, json)

        assertEquals(2, documents.size)
        assertEquals(BudgetType.Main, documents[0].type)
        assertEquals("Main.pdf", documents[0].file?.name)
        assertEquals(2048, documents[0].file?.sizeBytes)
        assertEquals("Camera", documents[1].departmentName)
        assertEquals("Ravi Menon", documents[1].uploadedByName)
    }

    /**
     * A deleted budget keeps its row with an empty attachment
     * (`BudgetPrimaryComponent.jsx:240-243`) — the document survives, the file
     * does not, and nothing must offer to open it.
     */
    @Test
    fun `an emptied attachment is no file at all`() {
        val body = json.parseToJsonElement("""{"budgets":[{"_id":"b3","budget_type":"main","attachment":{}}]}""")

        val document = documentsOf(body, json).single()

        assertNull(document.file)
    }

    /** The upload answers with the one document, not a list. */
    @Test
    fun `a bare document is read as a list of one`() {
        val body = json.parseToJsonElement("""{"_id":"b4","budget_type":"main","attachment":{"media":"k/a.pdf"}}""")

        assertEquals("b4", documentsOf(body, json).single().id)
    }

    /** A row with no id is a row nothing can address; it is dropped, not guessed at. */
    @Test
    fun `an id-less row is dropped`() {
        val body = json.parseToJsonElement("""{"budgets":[{"budget_type":"main"},{"_id":"b5","budget_type":"main"}]}""")

        assertEquals(listOf("b5"), documentsOf(body, json).map { it.id })
    }

    /** An unknown type still shows: the server may add a third budget tomorrow. */
    @Test
    fun `an unknown type survives`() {
        val body = json.parseToJsonElement("""{"budgets":[{"_id":"b6","budget_type":"unit"}]}""")

        assertEquals(BudgetType.Unknown, documentsOf(body, json).single().type)
    }

    /** A main budget carries no department; a department's carries one. */
    @Test
    fun `the post body names a department only when there is one`() {
        val file = BudgetFile(media = "k/a.pdf", name = "a.pdf")

        val main = postBody(BudgetType.Main, departmentId = "d9", file = file)
        val department = postBody(BudgetType.Department, departmentId = "d9", file = file)

        assertEquals("main", main["budget_type"]?.jsonPrimitive?.content)
        assertTrue("department_id" !in main, "a main budget belongs to the project, not a department")
        assertEquals("d9", department["department_id"]?.jsonPrimitive?.content)
        assertEquals("k/a.pdf", department["attachment"]?.jsonObject?.get("media")?.jsonPrimitive?.content)
    }

    /** Deleting one still sends a list, as the wire wants. */
    @Test
    fun `delete sends a list even for one`() {
        val body = deleteBody(listOf("b1"))

        assertEquals(1, body["budget_ids"]?.jsonArray?.size)
        assertEquals("b1", body["budget_ids"]?.jsonArray?.first()?.jsonPrimitive?.content)
    }
}
