package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.auth.data.AuthEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every auth and project URL, checked for being a URL.
 *
 * Written after `departments` shipped as the literal text `${'$'}{core}departments…`
 * — a `${'$'}` escape that survived an edit, so the string interpolated to its own
 * source. Ktor took it as a relative path, the call failed, and the join form's
 * department picker came up empty in a way that looked like a server with no
 * departments on it. Nothing else in the codebase would have noticed: the
 * caller maps failure to an empty list on purpose, because a production that
 * genuinely has no departments is a real state.
 *
 * So this asserts the shape rather than the spelling: an endpoint that did not
 * interpolate cannot start with the configured host.
 */
class AuthEndpointsTest {

    private val host = "https://core.example.test"

    private val endpoints = AuthEndpoints(
        AppConfig(
            environment = Environment.Develop,
            services = mapOf(
                ZillitService.Core to host,
                ZillitService.Units to "https://units.example.test",
            ),
            realtime = emptyMap(),
        ),
    )

    private fun coreEndpoints() = mapOf(
        "requestOtp" to endpoints.requestOtp,
        "verifyOtp" to endpoints.verifyOtp,
        "device" to endpoints.device,
        "recoveryByEmail" to endpoints.recoveryByEmail,
        "recoveryByCode" to endpoints.recoveryByCode,
        "projects" to endpoints.projects,
        "favouriteProject" to endpoints.favouriteProject,
        "projectTypes" to endpoints.projectTypes,
        "languages" to endpoints.languages,
        "projectByCode" to endpoints.projectByCode("ABC123"),
        "joinProject" to endpoints.joinProject,
        "departments" to endpoints.departments,
        "joinStatus" to endpoints.joinStatus,
    )

    @Test
    fun `every core endpoint is an absolute url on the configured host`() {
        coreEndpoints().forEach { (name, url) ->
            assertTrue(url.startsWith("$host/api/v2/"), "$name did not resolve: $url")
        }
    }

    @Test
    fun `no endpoint carries an uninterpolated template`() {
        // The failure mode this file exists for. Checked separately from the
        // prefix so a regression names itself rather than reading as a host
        // mismatch.
        (coreEndpoints() + ("units" to endpoints.units("p1"))).forEach { (name, url) ->
            assertTrue('$' !in url, "$name has an uninterpolated template: $url")
        }
    }

    @Test
    fun `departments asks for the roles nested`() {
        // Without it the join form needs a second call per department change,
        // and both other clients ask for it this way.
        assertEquals("$host/api/v2/departments?designations=true", endpoints.departments)
    }

    @Test
    fun `units live on their own service`() {
        assertTrue(endpoints.units("p1").startsWith("https://units.example.test/api/v2/unit"))
    }
}
