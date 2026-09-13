package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.feature.crewlist.data.UnitDto
import com.zillit.desktop.feature.crewlist.data.orderJson
import com.zillit.desktop.feature.crewlist.data.toBody
import com.zillit.desktop.feature.crewlist.data.toCompanyDetails
import com.zillit.desktop.feature.crewlist.data.toOrder
import com.zillit.desktop.feature.crewlist.data.toOrderedPerson
import com.zillit.desktop.feature.crewlist.data.toPatchBody
import com.zillit.desktop.feature.crewlist.data.toPdf
import com.zillit.desktop.feature.crewlist.domain.CompanyCustomField
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CrewDocumentRequest
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.HeaderSection
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.SectionOffset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The roster's nesting, the render body every call shares, and the company PATCH — from the web client. */
class CrewListWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the roster nests unit - department - people and keeps both addresses apart`() {
        // The client hands the serializer the UNWRAPPED `data` — a bare array.
        val units = json.decodeFromString(
            ListSerializer(UnitDto.serializer()),
            """
            [
              {"unit_name":"main_unit_label","departments":[
                {"department_name":"camera_department_label","users":[
                  {"user_id":"u1","full_name":"Aisha Khan","designation_name":"first_ac_label",
                   "phone":5550001,"country_code":"+44","primary_email":"a@crew.example",
                   "email":"aisha@zillit.org","joining_date":"12 Sep 2026",
                   "profile_picture":{"media":"p/1.jpg","bucket":"b","region":"r","thumbnail":"p/1t.jpg"}},
                  {"user_id":"u2","full_name":"Ravi","is_external_user":"true","email":"r@x.y"},
                  {"full_name":"No id"}
                ]}
              ]}
            ]
            """,
        ).map { it.toModel() }

        val members = units.single().departments.single().members
        assertEquals(2, members.size, "a row without an id cannot be placed")
        val aisha = members[0]
        assertEquals("5550001", aisha.phone, "a numeric phone still reads")
        assertEquals("a@crew.example", aisha.profileEmail)
        assertEquals("aisha@zillit.org", aisha.projectEmail)
        assertEquals("main_unit_label", aisha.unitName, "the unit rides down to its members")
        assertEquals("p/1.jpg", aisha.picture?.media)
        val ravi = members[1]
        assertTrue(ravi.isExternal, "a string flag still reads")
        assertEquals("r@x.y", ravi.profileEmail)
        assertEquals("", ravi.projectEmail)
    }

    @Test
    fun `the generate answer is kept whole and named the web's way`() {
        val data = json.parseToJsonElement(
            """{"media":"crew/Crew List 123.pdf","bucket":"b","region":"eu-west-2","file_size":1024}""",
        ).jsonObject
        val pdf = data.toPdf()
        assertEquals("crew/Crew List 123.pdf", pdf.media)
        assertEquals("Crew List.pdf", pdf.name, "no name on the wire falls back")
        assertEquals("1024", pdf.fileSize)
        assertEquals(data, pdf.attachment, "the Info post forwards the answer untouched")
    }

    @Test
    fun `the render body carries layout, overrides and the fixed flags`() {
        val layout = HeaderLayout().let {
            it.copy(logoSize = 220, offsets = it.offsets + (HeaderSection.Logo to SectionOffset(-12, 6)))
        }
        val body = CrewDocumentRequest(
            layout = layout,
            overrides = mapOf("u1" to MemberOverride(phone = "", countryCode = "+44")),
            hideInternalLines = true,
            hideExternalLabel = true,
        ).toBody()

        val header = body["header_layout"]!!.jsonObject
        assertEquals("left", header["logo"].toString().trim('"'))
        assertEquals("220", header["logoSize"].toString())
        assertEquals("""{"x":-12,"y":6}""", header["offsets"]!!.jsonObject["logo"].toString())
        assertEquals("""[["logo","company"],"title"]""", header["order"].toString())
        assertEquals("""[{"user_id":"u1","phone":"","country_code":"+44"}]""", body["member_overrides"].toString())
        assertEquals("true", body["hide_internal_lines"].toString())
        assertEquals("true", body["show_zillit_email"].toString())
        assertEquals("true", body["hide_external_label"].toString())

        val design = CrewDocumentRequest(layout = layout, stacked = true).toBody()
        assertEquals("""["title","logo","company"]""", design["header_layout"]!!.jsonObject["order"].toString())
        assertFalse("hide_external_label" in design, "previews never ask")
    }

    @Test
    fun `an order from the page reads bare strings and pairs, dropping unknown ids`() {
        val order = json.parseToJsonElement("""[["logo","title"],"company","header"]""").toOrder()
        assertEquals(listOf(listOf(HeaderSection.Logo, HeaderSection.Title), listOf(HeaderSection.Company)), order)
        assertEquals("""[["logo","title"],"company"]""", orderJson(order).toString())
    }

    @Test
    fun `the people order leaves out the removed, the pending and the gone`() {
        val rows = json.parseToJsonElement(
            """
            [{"user_id":"a","full_name":"Aisha","designation_name":"first_ac_label","status":"accepted"},
             {"user_id":"b","full_name":"Ben","status":"pending"},
             {"user_id":"c","full_name":"Cara","status":"Left"},
             {"full_name":"No id"}]
            """,
        ) as JsonArray
        val people = rows.mapNotNull { (it as JsonObject).toOrderedPerson() }
        assertEquals(listOf("a"), people.map { it.userId })
        assertEquals("first_ac_label", people.single().designation)
    }

    @Test
    fun `company details read from the project and patch every field back`() {
        val project = json.parseToJsonElement(
            """
            {"company_name":"India Take One","company_phone":"2071234567","company_country_code":"+44",
             "customFields":[{"label":"VAT","value":"GB1"},{"label":"","value":""}],
             "company_logo":{"media":"logo/a.png","bucket":"b","region":"r"}}
            """,
        ).jsonObject
        val details = project.toCompanyDetails()
        assertEquals("India Take One", details.name)
        assertEquals("logo/a.png", details.logo?.media)
        assertEquals(2, details.customFields.size)

        val patch = details.toPatchBody(newLogo = null)
        assertEquals("", patch["company_email"].toString().trim('"'), "an empty field clears")
        assertEquals(
            """[{"label":"VAT","value":"GB1","fieldType":"text"}]""",
            patch["customFields"].toString(),
            "empty rows are dropped",
        )
        assertNull(patch["company_logo"], "an unchanged logo is not re-sent")

        val withLogo = details.copy(customFields = listOf(CompanyCustomField("A", "B")))
            .toPatchBody(CompanyLogo(media = "logo/b.png", bucket = "b", region = "r"))
        val logo = withLogo["company_logo"]!!.jsonObject
        assertEquals("India Take One", logo["caption"].toString().trim('"'))
        assertFalse("thumbnail" in logo, "a missing thumbnail is omitted, never null")
        assertTrue(withLogo["customFields"] is JsonArray)
        assertTrue(project["company_logo"] is JsonObject)
    }
}
