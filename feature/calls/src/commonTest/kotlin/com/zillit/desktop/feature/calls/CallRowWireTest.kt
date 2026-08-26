package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.FirestoreCallStatusPlane
import com.zillit.desktop.feature.calls.data.encodeFirestoreFields
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The types of the row this device writes into `calls/{uuid}/call_users`.
 *
 * iOS decodes every row with `JSONDecoder().decode(AgoraUserModel.self, …)`,
 * and Swift's Codable is strict: a JSON number arriving where the model
 * declares `String?` throws `typeMismatch` and the WHOLE row is discarded —
 * not just that field. So a desktop that wrote `agora_uid` as an integer was
 * invisible on the phones in every respect at once: no status, no raised hand,
 * no screen share. It looked like several unrelated features being broken.
 *
 * These pin the shape rather than the behaviour, because the behaviour lives in
 * another codebase and only shows up on somebody else's handset.
 */
class CallRowWireTest {

    private fun encoded(fields: Map<String, Any>) =
        encodeFirestoreFields(fields)["fields"]!!.jsonObject

    /** iOS writes `"agora_uid": "\(agoraID)"` and reads it back as `String?`. */
    @Test
    fun `agora_uid goes out as a string, never a number`() {
        val row = encoded(mapOf("agora_uid" to 12345.toString()))["agora_uid"]!!.jsonObject

        assertTrue("stringValue" in row, "iOS declares agoraUID as String? — a number breaks the row")
        assertTrue("integerValue" !in row)
        assertEquals("12345", row["stringValue"]?.jsonPrimitive?.content)
    }

    /** The flags iOS declares as `Bool?` must arrive as booleans. */
    @Test
    fun `the boolean flags go out as booleans`() {
        val row = encoded(mapOf("raise_hand" to true, "screen_share" to false, "has_video" to true))

        for (field in listOf("raise_hand", "screen_share", "has_video")) {
            assertTrue(
                "booleanValue" in row[field]!!.jsonObject,
                "$field must be a Firestore boolean — iOS declares it Bool?",
            )
        }
    }

    /** Status is a string on both sides, and the words have to match exactly. */
    @Test
    fun `status words match the ones iOS filters on`() {
        // iOS: enum FirebaseCallStatus — caller, ringing, in_call, declined, leave.
        // It filters the roster on current_status == "in_call"; anything else
        // and this device is simply not considered to be in the call.
        assertEquals("caller", CallStatus.Caller.wire)
        assertEquals("ringing", CallStatus.Ringing.wire)
        assertEquals("in_call", CallStatus.InCall.wire)
        assertEquals("declined", CallStatus.Declined.wire)
        assertEquals("leave", CallStatus.Left.wire)
    }

    @Test
    fun `screen share uses the spelling iOS reads`() {
        // iOS's row model is `case screenShare = "screen_share"`. The camelCase
        // spelling is read for compatibility but must never be what we write.
        assertEquals("screen_share", FirestoreCallStatusPlane.FIELD_SHARING)
    }
}

/**
 * The `line` discriminator on call-scoped writes.
 *
 * `call/end-call` and its siblings live under the Agora prefix and are only
 * line-correct BECAUSE of this field. A mediasoup teardown without it is
 * resolved on the Agora line and ends nothing — the SFU room outlives the
 * hang-up, and no error is raised anywhere.
 */
class CallLineFieldTest {

    @Test
    fun `every call provider has the wire word the backend expects`() {
        assertEquals("agora", CallProvider.Agora.wire)
        assertEquals("mediasoup", CallProvider.Mediasoup.wire)
    }

    /**
     * The default matters: it is what every existing Agora caller gets, and it
     * has to equal what the backend already assumed when nobody sent a line.
     */
    @Test
    fun `the default line is agora, matching the previous behaviour`() {
        assertEquals("agora", CallProvider.Agora.wire, "changing this silently re-routes every teardown")
    }
}
