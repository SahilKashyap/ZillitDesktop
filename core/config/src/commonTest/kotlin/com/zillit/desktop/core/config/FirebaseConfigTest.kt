package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the Firebase triple from `zillit.properties`.
 *
 * The app id is the newest of the three and the only optional one: Remote
 * Config's client-fetch REST body requires it (see `AppUpdateChecker`), while
 * the Firestore call mirror and the chat presence feed need only the project id
 * and key. Making it mandatory would have switched calling off on every install
 * configured before update notices existed — which is the regression these
 * tests exist to prevent.
 */
class FirebaseConfigTest {

    private fun parse(vararg extra: Pair<String, String>) = ConfigParser.parse(
        Environment.Production,
        mapOf("PROD_BASE_URL" to "https://api.example.com") + extra,
    )

    @Test
    fun `all three values are read`() {
        val result = parse(
            "PROD_FIREBASE_PROJECT_ID" to "zillit-prod",
            "PROD_FIREBASE_API_KEY" to "key",
            "PROD_FIREBASE_APP_ID" to "1:1234567890:android:abcdef",
        )

        assertTrue(result is ZillitResult.Success)
        assertEquals(FirebaseConfig("zillit-prod", "key", "1:1234567890:android:abcdef"), result.data.firebase)
    }

    @Test
    fun `the app id is optional and its absence leaves the pair usable`() {
        val result = parse(
            "PROD_FIREBASE_PROJECT_ID" to "zillit-prod",
            "PROD_FIREBASE_API_KEY" to "key",
        )

        assertTrue(result is ZillitResult.Success)
        assertEquals("zillit-prod", result.data.firebase?.projectId)
        assertNull(result.data.firebase?.appId, "an absent app id must not be an empty string")
    }

    @Test
    fun `a blank app id line reads as absent`() {
        // The template ships `PROD_FIREBASE_APP_ID=` filled in by nobody. An
        // empty string would reach the Remote Config body and be rejected by
        // Google, which is a worse failure than simply staying switched off.
        val result = parse(
            "PROD_FIREBASE_PROJECT_ID" to "zillit-prod",
            "PROD_FIREBASE_API_KEY" to "key",
            "PROD_FIREBASE_APP_ID" to "   ",
        )

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.firebase?.appId)
    }

    @Test
    fun `an app id without the pair yields no Firebase configuration at all`() {
        val result = parse("PROD_FIREBASE_APP_ID" to "1:1234567890:android:abcdef")

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.firebase)
    }

    @Test
    fun `the app id is read from the active environment only`() {
        val result = parse(
            "PROD_FIREBASE_PROJECT_ID" to "zillit-prod",
            "PROD_FIREBASE_API_KEY" to "key",
            "QA_FIREBASE_APP_ID" to "1:9999999999:android:qaqaqa",
        )

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.firebase?.appId, "a QA app id must not be picked up by a production launch")
    }

    @Test
    fun `toString never prints the api key`() {
        val rendered = FirebaseConfig("zillit-prod", "super-secret-key", "1:1:android:a").toString()

        assertFalse(rendered.contains("super-secret-key"), "the api key reached a log line: $rendered")
    }
}
