package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.feature.crewlist.data.PdfDto
import com.zillit.desktop.feature.crewlist.data.UnitDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster's nesting and the generate answer, from the reference clients. */
class CrewListWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the roster nests unit - department - people`() {
        // The client hands the serializer the UNWRAPPED `data` — a bare array.
        val decoded = json.decodeFromString(
            ListSerializer(UnitDto.serializer()),
            """
            [
              {"unit_name":"main_unit_label","departments":[
                {"department_name":"camera_department_label","users":[
                  {"user_id":"u1","full_name":"Aisha Khan","designation_name":"first_ac_label",
                   "phone":"5550001","country_code":"+44","primary_email":"a@crew.example"},
                  {"user_id":"u2","full_name":"Ravi","is_external_user":true,"email":"r@x.y"},
                  {"full_name":"No id"}
                ]}
              ]}
            ]
            """,
        )
        val units = decoded.map { it.toModel() }

        val members = units.single().departments.single().members
        assertEquals(2, members.size, "a row without an id cannot be placed")
        assertEquals("a@crew.example", members[0].primaryEmail)
        assertTrue(members[1].isExternal)
        assertEquals("r@x.y", members[1].primaryEmail, "email backs up primary_email")
    }

    @Test
    fun `the generate answer is a stored file, name derived the web's way`() {
        val pdf = json.decodeFromString(
            PdfDto.serializer(),
            """{"media":"crew/Crew List 123.pdf","bucket":"b","region":"eu-west-2"}""",
        ).toModel()

        assertEquals("crew/Crew List 123.pdf", pdf.media)
        assertEquals("Crew List.pdf", pdf.name, "no name on the wire falls back")
    }
}
