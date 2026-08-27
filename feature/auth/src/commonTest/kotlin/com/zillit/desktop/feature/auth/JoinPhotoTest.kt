package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.data.JoinRequestDto
import com.zillit.desktop.feature.auth.data.toRequestDto
import com.zillit.desktop.feature.auth.domain.ChosenPhoto
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The profile picture on a join request.
 *
 * The picture is uploaded first and the request names where it landed —
 * `profile_picture: {media, thumbnail, bucket, region}`, identical in Android's
 * `ProfilePictureRequest` and iOS's `ProfilePictureRequestModel`.
 *
 * The absent-versus-null distinction is the part worth pinning: this endpoint
 * treats an empty picture object as "attach a blank avatar", so a joiner who
 * skipped the photo must send no key at all rather than four empty strings.
 */
class JoinPhotoTest {

    private val json = Json { explicitNulls = false; encodeDefaults = false }

    private val draft = JoinDraft(
        firstName = "Peach",
        lastName = "Android",
        departmentId = "d1",
        designationId = "r1",
        unitId = "u1",
    )

    private fun body(draft: JoinDraft): JsonObject =
        json.encodeToJsonElement(JoinRequestDto.serializer(), draft.toRequestDto()) as JsonObject

    @Test
    fun `a stored picture travels as four keys`() {
        val withPhoto = draft.copy(
            photo = JoinPhoto(
                media = "profile-picture/abc/selfie.jpg",
                thumbnail = "profile-picture/abc/selfie.jpg",
                bucket = "zillit-dev-uploads",
                region = "ap-south-1",
            ),
        )

        val picture = body(withPhoto)["profile_picture"] as JsonObject

        assertEquals(
            setOf("media", "thumbnail", "bucket", "region"),
            picture.keys,
            "the wire shape is the phones' ProfilePictureRequest, exactly",
        )
        assertTrue(picture.toString().contains("profile-picture/abc/selfie.jpg"))
        assertTrue(picture.toString().contains("ap-south-1"))
    }

    @Test
    fun `no picture means no key at all`() {
        // Not `"profile_picture": null`, and not an object of empty strings:
        // either attaches a blank avatar to the crew list.
        assertFalse("profile_picture" in body(draft))
    }

    @Test
    fun `the rest of the request is unchanged by a picture`() {
        val withPhoto = draft.copy(
            photo = JoinPhoto("m", "t", "b", "r"),
        )
        val plain = body(draft)
        val decorated = body(withPhoto)

        (plain.keys).forEach { key ->
            assertEquals(plain[key], decorated[key], "$key changed when a photo was added")
        }
    }

    @Test
    fun `a chosen photo never prints its bytes`() {
        // Join requests are logged on failure, and a base64 portrait in the
        // log is both useless and personal data.
        val chosen = ChosenPhoto("selfie.jpg", "image/jpeg", ByteArray(2048))

        val printed = chosen.toString()

        assertTrue(printed.contains("selfie.jpg"))
        assertTrue(printed.contains("2048"))
        assertFalse(printed.contains("["), "no array dump in $printed")
    }

    @Test
    fun `a draft never prints the name but does say whether a photo is set`() {
        val printed = draft.copy(photo = JoinPhoto("m", "t", "b", "r")).toString()

        assertFalse(printed.contains("Peach"))
        assertTrue(printed.contains("photo=true"))
    }

    @Test
    fun `a storage failure is a failure, not a silent success`() {
        // The form has to be able to tell the difference: a picture that did
        // not save must leave the draft without one.
        val failing = com.zillit.desktop.feature.auth.domain.JoinPhotoStore {
            ZillitResult.Failure(ZillitError.Storage(technical = "no bucket", userMessage = "nope"))
        }

        val outcome = kotlinx.coroutines.runBlocking {
            failing.store(ChosenPhoto("a.jpg", "image/jpeg", ByteArray(1)))
        }

        assertTrue(outcome is ZillitResult.Failure)
    }
}
