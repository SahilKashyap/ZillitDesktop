package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Loads the developer's real `~/.zillit/zillit.properties` and reports what
 * resolved. Skips when the file is absent, so CI is unaffected.
 *
 * This is a diagnostic, not a correctness test — the correctness tests use
 * fixed inputs. Its job is to catch "the file you were handed does not load"
 * before someone spends an afternoon on a blank sign-in screen.
 */
class RealConfigDiagnostic {

    @Test
    fun `report what the local config resolves to`() {
        val file = File(System.getProperty("user.home"), ".zillit/zillit.properties")
        if (!file.isFile) {
            println("No ~/.zillit/zillit.properties — skipping")
            return
        }

        val properties = PropertiesParser.parse(file.readText())
        println("Parsed ${properties.size} keys from ${file.path}")

        Environment.entries.forEach { environment ->
            when (val result = ConfigParser.parse(environment, properties)) {
                is ZillitResult.Success -> {
                    val config = result.data
                    val missing = ZillitService.entries.filterNot { it in config.services.keys }
                    println(
                        "  ${environment.id.padEnd(8)} OK  " +
                            "${config.services.size}/${ZillitService.entries.size} services, " +
                            "${config.realtime.size}/${ZillitRealtimeEndpoint.entries.size} realtime",
                    )
                    println("    core    = ${config.apiV2()}")
                    if (missing.isNotEmpty()) {
                        println("    missing = ${missing.joinToString { it.configKey }}")
                    }
                }

                is ZillitResult.Failure ->
                    println("  ${environment.id.padEnd(8)} FAILED  ${result.error.technical}")
            }
        }

        // The one hard assertion: every environment must at least load.
        val failures = Environment.entries.filter {
            ConfigParser.parse(it, properties) is ZillitResult.Failure
        }
        assertTrue(failures.isEmpty(), "these environments failed to load: ${failures.map { it.id }}")
    }
}
