package com.zillit.desktop.feature.distribution

import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.feature.distribution.data.UserAccessDto
import com.zillit.desktop.feature.distribution.data.refuseStatusZero
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The grid feed's shapes and the row rule, from the reference clients. */
class DistributionWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the all-access feed decodes, sections discriminate`() {
        // The client hands the serializer the UNWRAPPED `data` — a bare array.
        val decoded = json.decodeFromString(
            ListSerializer(UserAccessDto.serializer()),
            """
            [
              {"user_id":"u1","user_name":"Gaffer","status":"accepted","distributions":[
                {"unit_id":"h1","unit_name":"bulletin_label","distribution_access":true,"home":true},
                {"unit_id":"t1","unit_name":"location_tool_label","distribution_access":false,
                 "tool":true,"to_updatable":false}
              ]},
              {"user_id":"u2","user_name":"Vendor","user_type":"external","outsider":"outsider"},
              {"user_id":"u3","user_name":"Left crew","status":"removed"}
            ]
            """,
        )
        val users = decoded.mapNotNull { it.toModel() }

        assertEquals(3, users.size)
        val gaffer = users[0]
        assertTrue(gaffer.isListed)
        assertTrue(gaffer.units.single { it.isHome }.toEnabled)
        assertFalse(gaffer.units.single { it.isTool }.toUpdatable, "the lock flag holds the switch")

        assertTrue(users[1].isExternal)
        assertTrue(users[1].isListed, "externals list regardless of status")
        assertFalse(users[2].isListed, "removed crew do not")
    }

    @Test
    fun `an absent updatable flag means allowed`() {
        val decoded = json.decodeFromString(
            ListSerializer(UserAccessDto.serializer()),
            """[{"user_id":"u1","distributions":[{"unit_id":"x","unit_name":"n","home":true}]}]""",
        )
        val unit = decoded.single().toModel()!!.units.single()

        assertTrue(unit.toUpdatable)
        assertFalse(unit.toEnabled)
    }

    @Test
    fun `a status-zero answer becomes a refusal with its message`() {
        val refused = ZillitResult.Success(ApiEnvelope(status = 0, message = "distribution_access_denied"))
            .refuseStatusZero()
        val passed = ZillitResult.Success(ApiEnvelope(status = 1)).refuseStatusZero()

        assertTrue(refused is ZillitResult.Failure)
        assertEquals(
            "distribution_access_denied",
            (refused as ZillitResult.Failure).error.userMessage,
        )
        assertTrue(passed is ZillitResult.Success)
    }

    @Test
    fun `the outsider literal is what marks an external`() {
        assertTrue(DistributionUser("u", outsider = "outsider").isExternal)
        assertFalse(DistributionUser("u", outsider = "").isExternal)
        assertTrue(DistributionUser("u", userType = "external").isExternal)
    }
}
