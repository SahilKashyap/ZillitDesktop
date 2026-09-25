package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.config.FirebaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker against a faked Firebase.
 *
 * The contract these all serve: **anything short of a clear "there is a newer
 * build" must resolve to [UpdateStatus.Unknown]**. A false "you are out of
 * date" is unfalsifiable from where the reader sits, and one of them teaches
 * people to dismiss the banner forever.
 */
class AppUpdateCheckerTest {

    private val firebase = FirebaseConfig(
        projectId = "zillit-test",
        apiKey = "test-api-key",
        appId = "1:1234567890:android:abcdef",
    )

    @Test
    fun `latest above installed is an available update`() = runTest {
        val status = check(installed = "1.1.0", entries = """"desktop_latest_version":"1.2.0"""")

        assertEquals(UpdateStatus.Available("1.2.0", null), status)
    }

    @Test
    fun `latest equal to installed is up to date`() = runTest {
        val status = check(installed = "1.2.0", entries = """"desktop_latest_version":"1.2.0"""")

        assertEquals(UpdateStatus.UpToDate, status)
    }

    /** The lexicographic trap, end to end. */
    @Test
    fun `1_10 is newer than 1_9 across the wire`() = runTest {
        val status = check(installed = "1.9.0", entries = """"desktop_latest_version":"1.10.0"""")

        assertEquals(UpdateStatus.Available("1.10.0", null), status)
    }

    @Test
    fun `installed below the floor is required`() = runTest {
        val status = check(
            installed = "1.0.0",
            entries = """"desktop_latest_version":"1.2.0","desktop_min_version":"1.1.0"""",
        )

        assertEquals(UpdateStatus.Required("1.2.0", null), status)
    }

    /**
     * Required wins outright.
     *
     * A build below the floor is also behind the latest, so both branches
     * match. Reporting the softer one would put "an update is available" in
     * front of somebody whose app is about to stop working.
     */
    @Test
    fun `required beats available when both apply`() = runTest {
        val status = check(
            installed = "0.9.0",
            entries = """"desktop_latest_version":"2.0.0","desktop_min_version":"1.5.0"""",
        )

        assertTrue(status is UpdateStatus.Required, "expected Required, got $status")
    }

    @Test
    fun `a floor with no latest published still names the floor`() = runTest {
        val status = check(installed = "1.0.0", entries = """"desktop_min_version":"1.1.0"""")

        assertEquals(UpdateStatus.Required("1.1.0", null), status)
    }

    /**
     * The template as it really was, on every environment, on 2026-09-14.
     *
     * The values carry literal quotes because the console was typed into with
     * them. Prod published `"1.0.3"` over a floor of `"1.0.1"`, an install on
     * 1.0.1 compared `"1.0.3"` as 0.0.3 and heard nothing — this is the whole
     * "update feature not working" report, reproduced.
     */
    @Test
    fun `quoted console values are read as the numbers inside them`() = runTest {
        val status = check(
            installed = "1.0.1",
            entries = """"desktop_latest_version":"\"1.0.3\"","desktop_min_version":"\"1.0.1\"",""" +
                """"desktop_download_url":"\"https://drive.example/get\""""",
        )

        assertEquals(UpdateStatus.Available("1.0.3", "https://drive.example/get"), status)
    }

    /** Dev's `desktop_download_url` is the two characters `""`; that is no URL, and falls back. */
    @Test
    fun `a quoted-empty URL falls back`() = runTest {
        val status = check(
            installed = "1.1.0",
            entries = """"desktop_latest_version":"1.2.0","desktop_download_url":"\"\""""",
            fallback = "https://zillit.example.com/download",
        )

        assertEquals(UpdateStatus.Available("1.2.0", "https://zillit.example.com/download"), status)
    }

    // -- per-platform keys ---------------------------------------------------

    @Test
    fun `a Windows install takes the _windows URL over the plain one`() = runTest {
        val entries = """"desktop_latest_version":"1.2.0",""" +
            """"desktop_download_url":"https://dl.example/Zillit.dmg",""" +
            """"desktop_download_url_windows":"https://dl.example/Zillit.msi""""

        assertEquals(
            UpdateStatus.Available("1.2.0", "https://dl.example/Zillit.msi"),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.Windows),
        )
        assertEquals(
            UpdateStatus.Available("1.2.0", "https://dl.example/Zillit.dmg"),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.MacOs),
        )
    }

    /** One platform's build lags: its own latest/floor decide, the plain keys are everyone else's. */
    @Test
    fun `a platform version overrides the plain one`() = runTest {
        val entries = """"desktop_latest_version":"1.3.0","desktop_min_version":"1.2.0",""" +
            """"desktop_latest_version_windows":"1.1.0","desktop_min_version_windows":"1.0.0""""

        assertEquals(
            UpdateStatus.UpToDate,
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.Windows),
        )
        assertEquals(
            UpdateStatus.Required("1.3.0", null),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.MacOs),
        )
    }

    @Test
    fun `an empty platform key falls through to the plain one`() = runTest {
        val entries = """"desktop_latest_version":"1.2.0",""" +
            """"desktop_download_url":"https://dl.example/get","desktop_download_url_mac":"""""

        assertEquals(
            UpdateStatus.Available("1.2.0", "https://dl.example/get"),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.MacOs),
        )
    }

    @Test
    fun `an unknown platform reads only the plain keys`() = runTest {
        val entries = """"desktop_latest_version":"1.2.0","desktop_latest_version_mac":"9.0.0""""

        assertEquals(
            UpdateStatus.Available("1.2.0", null),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.Unknown),
        )
    }

    // -- the silence contract ------------------------------------------------

    @Test
    fun `NO_TEMPLATE is unknown, not up to date`() = runTest {
        val status = check(installed = "1.0.0", body = """{"state":"NO_TEMPLATE"}""")

        assertEquals(UpdateStatus.Unknown, status)
    }

    @Test
    fun `an empty entries object is unknown`() = runTest {
        val status = check(installed = "1.0.0", body = """{"entries":{},"state":"UPDATE"}""")

        assertEquals(UpdateStatus.Unknown, status)
    }

    @Test
    fun `a template with no desktop keys is unknown`() = runTest {
        // Android's keys are in the same template. Reading a template that says
        // nothing about desktop as "you are current" would be a guess.
        val status = check(installed = "1.0.0", entries = """"android_force_update":"true"""")

        assertEquals(UpdateStatus.Unknown, status)
    }

    @Test
    fun `an HTTP failure is unknown, never a false alarm`() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.Forbidden) })

        assertEquals(UpdateStatus.Unknown, checker(client, installed = "1.0.0").check())
    }

    @Test
    fun `a transport failure is unknown`() = runTest {
        val client = HttpClient(MockEngine { error("the machine is offline") })

        assertEquals(UpdateStatus.Unknown, checker(client, installed = "1.0.0").check())
    }

    @Test
    fun `unparsable JSON is unknown`() = runTest {
        val status = check(installed = "1.0.0", body = "<html>proxy sign-in</html>")

        assertEquals(UpdateStatus.Unknown, status)
    }

    // -- the off switches ----------------------------------------------------

    @Test
    fun `a missing app id is unknown and makes no request`() = runTest {
        val calls = mutableListOf<String>()
        val checker = AppUpdateChecker(
            httpClient = counting(calls),
            firebase = FirebaseConfig("zillit-test", "test-api-key", appId = null),
            installedVersion = { "1.0.0" },
            instanceId = { INSTANCE_ID },
            fallbackDownloadUrl = { null },
        )

        assertEquals(UpdateStatus.Unknown, checker.check())
        assertTrue(calls.isEmpty(), "the updater must not call Google without an app id")
    }

    @Test
    fun `no Firebase configuration at all is unknown and makes no request`() = runTest {
        val calls = mutableListOf<String>()
        val checker = AppUpdateChecker(
            httpClient = counting(calls),
            firebase = null,
            installedVersion = { "1.0.0" },
            instanceId = { INSTANCE_ID },
            fallbackDownloadUrl = { null },
        )

        assertEquals(UpdateStatus.Unknown, checker.check())
        assertTrue(calls.isEmpty())
    }

    /**
     * `:desktopApp:run` has no `jpackage.app-version`, so there is nothing to
     * compare. Nagging a developer about their own working copy is noise.
     */
    @Test
    fun `an unpackaged run is unknown and makes no request`() = runTest {
        val calls = mutableListOf<String>()

        assertEquals(UpdateStatus.Unknown, checker(counting(calls), installed = null).check())
        assertTrue(calls.isEmpty())
    }

    // -- the download URL ----------------------------------------------------

    @Test
    fun `the remote config URL is used when present`() = runTest {
        val status = check(
            installed = "1.1.0",
            entries = """"desktop_latest_version":"1.2.0","desktop_download_url":"https://rc.example.com/get"""",
            fallback = "https://zillit.example.com/download",
        )

        assertEquals(UpdateStatus.Available("1.2.0", "https://rc.example.com/get"), status)
    }

    @Test
    fun `the URL falls back to the Zillit configuration when the key is absent`() = runTest {
        val status = check(
            installed = "1.1.0",
            entries = """"desktop_latest_version":"1.2.0"""",
            fallback = "https://zillit.example.com/download",
        )

        assertEquals(UpdateStatus.Available("1.2.0", "https://zillit.example.com/download"), status)
    }

    /**
     * A remote value that is not https is dropped, and the fallback is tried.
     *
     * The value is typed into a console and ends up in a browser launcher; a
     * `file:` URL there would turn a config field into a way to open something
     * local.
     */
    @Test
    fun `a non-https remote URL is refused and falls back`() = runTest {
        val status = check(
            installed = "1.1.0",
            entries = """"desktop_latest_version":"1.2.0","desktop_download_url":"file:///etc/passwd"""",
            fallback = "https://zillit.example.com/download",
        )

        assertEquals(UpdateStatus.Available("1.2.0", "https://zillit.example.com/download"), status)
    }

    @Test
    fun `no usable URL anywhere still reports the version`() = runTest {
        val status = check(
            installed = "1.1.0",
            entries = """"desktop_latest_version":"1.2.0"""",
            fallback = "http://insecure.example.com/download",
        )

        assertEquals(UpdateStatus.Available("1.2.0", null), status)
    }

    // -- the wire ------------------------------------------------------------

    @Test
    fun `the request is the documented client fetch`() = runTest {
        var url = ""
        var body = ""
        val client = HttpClient(
            MockEngine { request ->
                url = request.url.toString()
                body = request.body.readText()
                respond(
                    content = """{"entries":{"desktop_latest_version":"1.0.0"},"state":"UPDATE"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        checker(client, installed = "1.0.0").check()

        assertTrue(
            url.startsWith(
                "https://firebaseremoteconfig.googleapis.com/v1/projects/zillit-test/namespaces/firebase:fetch",
            ),
            "unexpected endpoint: $url",
        )
        assertTrue(url.contains("key=test-api-key"), "the api key must travel as the documented query parameter")
        assertTrue(body.contains(""""appId":"1:1234567890:android:abcdef""""), "body was $body")
        assertTrue(body.contains(""""appInstanceId":"$INSTANCE_ID""""), "body was $body")
        assertTrue(body.contains(""""languageCode":"en""""), "body was $body")
    }

    /**
     * Firebase buckets percentage rollouts by the instance id, so it must be
     * whatever the caller persisted — never invented here.
     */
    @Test
    fun `the instance id comes from the caller on every fetch`() = runTest {
        val seen = mutableListOf<String>()
        val client = counting(seen)
        val checker = checker(client, installed = "1.0.0")

        checker.check()
        checker.check()

        assertEquals(2, seen.size)
        assertTrue(seen.all { it.contains(INSTANCE_ID) }, "the persisted id must be reused: $seen")
    }

    // -- helpers -------------------------------------------------------------

    /** A Mac gets the .dmg and its digest; a Windows install the .msi; a half-published pair is no installer. */
    @Test
    fun `installer keys follow the platform and need their digest`() = runTest {
        val mac = "a".repeat(64)
        val win = "b".repeat(64)
        val entries = """"desktop_latest_version":"1.2.0",""" +
            """"desktop_installer_url_mac":"https://cdn.example/Z.dmg","desktop_installer_sha256_mac":"$mac",""" +
            """"desktop_installer_url_windows":"https://cdn.example/Z.msi""""

        assertEquals(
            UpdateStatus.Available("1.2.0", null, InstallerRef("https://cdn.example/Z.dmg", mac)),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.MacOs),
        )
        assertEquals(
            UpdateStatus.Available("1.2.0", null, null),
            check(installed = "1.1.0", entries = entries, os = OperatingSystem.Windows),
        )
        assertEquals(
            UpdateStatus.Available("1.2.0", null, InstallerRef("https://cdn.example/Z.msi", win)),
            check(
                installed = "1.1.0",
                entries = "$entries,\"desktop_installer_sha256_windows\":\"$win\"",
                os = OperatingSystem.Windows,
            ),
        )
    }

    private suspend fun check(
        installed: String?,
        entries: String? = null,
        body: String? = null,
        fallback: String? = null,
        os: OperatingSystem = OperatingSystem.Unknown,
    ): UpdateStatus {
        val payload = body ?: """{"entries":{$entries},"state":"UPDATE"}"""
        val client = HttpClient(
            MockEngine {
                respond(content = payload, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        return checker(client, installed, fallback, os).check()
    }

    private fun checker(
        client: HttpClient,
        installed: String?,
        fallback: String? = null,
        os: OperatingSystem = OperatingSystem.Unknown,
    ) = AppUpdateChecker(
        httpClient = client,
        firebase = firebase,
        installedVersion = { installed },
        instanceId = { INSTANCE_ID },
        fallbackDownloadUrl = { fallback },
        os = os,
    )

    /** Records every request body it is sent, so "no request" is assertable. */
    private fun counting(into: MutableList<String>) = HttpClient(
        MockEngine { request ->
            into += request.body.readText()
            respond("""{"entries":{"desktop_latest_version":"1.0.0"},"state":"UPDATE"}""")
        },
    )

    /**
     * The request body as text.
     *
     * `OutgoingContent.toString()` truncates to the first thirty characters,
     * which silently passes any `contains` assertion about the tail of the JSON.
     */
    private fun OutgoingContent.readText(): String = (this as? TextContent)?.text.orEmpty()

    private companion object {
        const val INSTANCE_ID = "stable-install-id"
    }
}
