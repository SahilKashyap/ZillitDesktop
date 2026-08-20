package com.zillit.desktop.feature.externalusers

import com.zillit.desktop.feature.externalusers.data.ExternalUserDto
import com.zillit.desktop.feature.externalusers.data.toBody
import com.zillit.desktop.feature.externalusers.domain.CREW_TYPE
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.LabeledValue
import com.zillit.desktop.feature.externalusers.domain.VENDOR_TYPE
import com.zillit.desktop.feature.externalusers.domain.validationErrors
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire truths from the reference clients: the envelope, the always-sent
 * body keys, the free-text type rule, and the soft-delete tombstone.
 */
class ExternalUsersWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `rows decode, tombstoned rows vanish`() {
        // The client hands the serializer the UNWRAPPED `data` — a bare array.
        val decoded = json.decodeFromString(
            ListSerializer(ExternalUserDto.serializer()),
            """
            [
              {"_id":"e1","full_name":"Grip Hire Ltd","email":"ops@griphire.example",
               "external_user_type":"vender_label","updated_on":1700000000000,
               "other_info":[{"label":"Account","value":"GH-42"}],"created_by":"u9"},
              {"_id":"e2","full_name":"Gone","email":"x@y.z","deleted_on":1700000000001},
              {"full_name":"No id — unplaceable"}
            ]
            """,
        )
        val rows = decoded.mapNotNull { it.toModel() }

        assertEquals(1, rows.size)
        val vendor = rows.single()
        assertEquals("e1", vendor.id)
        assertEquals(ExternalUserBucket.Vendor, ExternalUserBucket.of(vendor.userType))
        assertEquals(listOf(LabeledValue("Account", "GH-42")), vendor.otherInfo)
        assertEquals("u9", vendor.createdBy)
    }

    @Test
    fun `the body always carries every key, the web's encoding`() {
        val body = ExternalUser(id = "e1", fullName = "A", email = "a@b.co").toBody(includeId = false)
        val keys = body.jsonObject.keys

        assertEquals(
            setOf(
                "full_name", "email", "phone", "gender", "country_code",
                "external_user_type", "department_id", "designation_id", "other_info",
            ),
            keys,
        )
    }

    @Test
    fun `an update names its record, half-filled info rows stay off the wire`() {
        val body = ExternalUser(
            id = "e1",
            fullName = "A",
            email = "a@b.co",
            otherInfo = listOf(LabeledValue("Full", "row"), LabeledValue("Half", "")),
        ).toBody(includeId = true)

        assertEquals("e1", body.jsonObject["external_user_id"]!!.toString().trim('"'))
        assertEquals(1, body.jsonObject["other_info"]!!.jsonArray.size)
    }

    @Test
    fun `the type rule - known labels are themselves, text is Others, blank is Crew`() {
        assertEquals(ExternalUserBucket.Crew, ExternalUserBucket.of(CREW_TYPE))
        assertEquals(ExternalUserBucket.Vendor, ExternalUserBucket.of(VENDOR_TYPE))
        assertEquals(ExternalUserBucket.Others, ExternalUserBucket.of("Caterer"))
        assertEquals(ExternalUserBucket.Crew, ExternalUserBucket.of(""))
    }

    @Test
    fun `validation - the phone pair, the crew department, the email`() {
        val ok = ExternalUser(fullName = "A", email = "a@b.co", userType = "Caterer")
        assertTrue(ok.validationErrors().isEmpty())

        assertTrue("countryCode" in ExternalUser(fullName = "A", email = "a@b.co", userType = "T", phone = "12345").validationErrors())
        assertTrue("phone" in ExternalUser(fullName = "A", email = "a@b.co", userType = "T", countryCode = "+44").validationErrors())
        assertTrue("departmentId" in ExternalUser(fullName = "A", email = "a@b.co", userType = CREW_TYPE).validationErrors())
        assertTrue("email" in ExternalUser(fullName = "A", email = "nope").validationErrors())
        assertTrue("otherInfo0" in ExternalUser(
            fullName = "A", email = "a@b.co", userType = "T",
            otherInfo = listOf(LabeledValue("only title", "")),
        ).validationErrors())
    }
}
