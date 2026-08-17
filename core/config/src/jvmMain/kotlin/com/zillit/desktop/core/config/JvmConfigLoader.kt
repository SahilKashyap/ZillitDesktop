package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import java.io.File

/**
 * Loads configuration from disk.
 *
 * Resolution order, first hit wins:
 *  1. `-Dzillit.config=<path>` system property
 *  2. `ZILLIT_CONFIG` environment variable
 *  3. `zillit.properties` next to the executable
 *  4. `~/.zillit/zillit.properties`
 *
 * Packaged builds that opted into `-PzillitBundleConfig` land here through
 * candidate 1: jpackage bakes `-Dzillit.config=$APPDIR/resources/…` into the
 * launcher, so the bundled copy is just a file like any other — no separate
 * code path to drift.
 *
 * Environment is chosen by `-Dzillit.env` / `ZILLIT_ENV`, defaulting to
 * production so a mistake fails onto the strictest settings rather than
 * silently pointing a user at QA.
 */
class JvmConfigLoader(
    private val explicitPath: String? = null,
) : ConfigLoader {

    override fun load(): ZillitResult<AppConfig> {
        val environment = Environment.fromId(
            System.getProperty(PROPERTY_ENV) ?: System.getenv(ENV_ENV),
        )

        val file = resolveFile()
            ?: return ZillitResult.Failure(
                ZillitError.Validation(
                    userMessage = "Zillit is not configured. Contact your administrator.",
                    technical = "No config file found. Looked for: ${candidatePaths().joinToString()}",
                ),
            )

        return runCatching { file.readText() }
            .fold(
                onSuccess = { raw ->
                    ZillitLog.i(TAG) { "Loaded configuration from ${file.path} (${environment.id})" }
                    val properties = ConfigParser.parseProperties(raw)

                    // A key of the wrong length is dropped rather than used, and
                    // the app then falls back to the keychain. Without this line
                    // that looks identical to having configured no key at all —
                    // and the symptom, a 401 on every request, points nowhere
                    // near a typo in a properties file.
                    if (ConfigParser.headerKeyIsMalformed(properties, environment)) {
                        ZillitLog.w(TAG) {
                            "${environment.propertyPrefix}_${HeaderKeyMaterial.KEY_SUFFIX} / " +
                                "${HeaderKeyMaterial.IV_SUFFIX} are present but not " +
                                "${HeaderKeyMaterial.KEY_LENGTH}/${HeaderKeyMaterial.IV_LENGTH} " +
                                "characters — ignoring them and falling back to the keychain"
                        }
                    }

                    ConfigParser.parse(environment, properties)
                },
                onFailure = { throwable ->
                    ZillitResult.Failure(
                        ZillitError.Validation(
                            userMessage = "Zillit could not read its configuration.",
                            technical = "Reading ${file.path} failed: ${throwable.message}",
                        ),
                    )
                },
            )
    }

    private fun resolveFile(): File? =
        candidatePaths().map(::File).firstOrNull { it.isFile && it.canRead() }

    private fun candidatePaths(): List<String> = listOfNotNull(
        explicitPath,
        System.getProperty(PROPERTY_CONFIG),
        System.getenv(ENV_CONFIG),
        File(System.getProperty("user.dir"), FILE_NAME).path,
        File(System.getProperty("user.home"), ".zillit/$FILE_NAME").path,
    )

    private companion object {
        const val TAG = "ConfigLoader"
        const val FILE_NAME = "zillit.properties"
        const val PROPERTY_CONFIG = "zillit.config"
        const val PROPERTY_ENV = "zillit.env"
        const val ENV_CONFIG = "ZILLIT_CONFIG"
        const val ENV_ENV = "ZILLIT_ENV"
    }
}
