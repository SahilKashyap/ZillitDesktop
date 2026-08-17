package com.zillit.desktop.core.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps `zillit.properties.template` honest.
 *
 * A template that silently falls behind the code is worse than none: someone
 * fills it in, the app starts, and a feature fails at runtime with "no endpoint
 * configured for X" — long after the person who could have fixed it moved on.
 * These tests fail the build the moment a service is added without a matching
 * template line.
 */
class ConfigTemplateTest {

    private val template: File = locateTemplate()

    @Test
    fun `the template exists`() {
        assertTrue(template.isFile, "expected a template at ${template.path}")
    }

    @Test
    fun `every service has a template line`() {
        val declared = template.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PROD_") }
            .map { it.substringBefore('=').removePrefix("PROD_") }
            .toSet()

        val missing = ZillitService.entries.map { it.configKey }.filterNot { it in declared }

        assertTrue(missing.isEmpty(), "services missing from the template: $missing")
    }

    @Test
    fun `every realtime endpoint has a template line`() {
        val declared = template.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PROD_") }
            .map { it.substringBefore('=').removePrefix("PROD_") }
            .toSet()

        val missing = ZillitRealtimeEndpoint.entries.map { it.configKey }.filterNot { it in declared }

        assertTrue(missing.isEmpty(), "realtime endpoints missing from the template: $missing")
    }

    @Test
    fun `the template carries no secret values`() {
        // The template is committed, and it now names the header key on
        // purpose — so the guard has to be about VALUES, not key names. Any
        // line whose name looks secret must have an empty right-hand side.
        val secretish = Regex("(?i)(ENCRYPTION_KEY|IV_KEY|PASSWORD|SECRET|TOKEN)")

        val populated = template.readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .filter { secretish.containsMatchIn(it.substringBefore('=')) }
            .filter { it.substringAfter('=', "").isNotBlank() }

        assertTrue(populated.isEmpty(), "template has a filled-in secret: $populated")
    }

    @Test
    fun `the template offers the header key lines`() {
        // Without these the only route is the keychain, and someone copying an
        // Android local.properties across would silently lose the key.
        val text = template.readText()

        assertTrue(text.contains("PROD_ENCRYPTION_KEY="), "no PROD_ENCRYPTION_KEY line")
        assertTrue(text.contains("PROD_IV_ENCRYPTION_KEY="), "no PROD_IV_ENCRYPTION_KEY line")
    }

    @Test
    fun `the template parses into a valid config once filled in`() {
        // Proves the documented key shape actually works — the template and the
        // parser agreeing is the whole point.
        val filled = ZillitService.entries.associate { "PROD_${it.configKey}" to "https://example.com" } +
            ZillitRealtimeEndpoint.entries.associate { "PROD_${it.configKey}" to "wss://example.com" }

        val result = ConfigParser.parse(Environment.Production, filled)

        assertTrue(result is com.zillit.desktop.core.common.ZillitResult.Success, "template shape failed to parse")
        assertEquals(ZillitService.entries.size, result.data.services.size)
    }

    /**
     * Walks up from the module directory to the repo root.
     *
     * Gradle runs tests with the module as the working directory, and hardcoding
     * `../../` breaks the moment the module moves.
     */
    private fun locateTemplate(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, TEMPLATE_NAME)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File(TEMPLATE_NAME)
    }

    private companion object {
        const val TEMPLATE_NAME = "zillit.properties.template"
    }
}
