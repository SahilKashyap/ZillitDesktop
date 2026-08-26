package com.zillit.desktop.feature.auth.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * A profile picture already in storage, as the join request names it.
 *
 * Four fields, because the server is told *where* the file is rather than sent
 * the file: the client uploads to S3 first and then hands over the keys. Both
 * other clients send exactly this object under `profile_picture` — Android's
 * `ProfilePictureRequest` and iOS's `ProfilePictureRequestModel`, whose
 * `media` key is spelled `profilePic` in Swift and `media` on the wire.
 */
data class JoinPhoto(
    /** The object key for the full-size image. */
    val media: String,
    /** The object key for the small one crew lists draw. */
    val thumbnail: String,
    val bucket: String,
    val region: String,
)

/**
 * An image the user chose, before it goes anywhere.
 *
 * Desktop has no selfie step. The phones open the camera — Android walks the
 * user through `FaceRecognitionActivity` and iOS asks for a photo — because a
 * phone is a camera; a workstation is not, and demanding a webcam to join a
 * production would stop people joining. The picture is chosen from disk
 * instead, and everything after that is identical.
 */
class ChosenPhoto(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    /** Never prints the bytes. */
    override fun toString(): String = "ChosenPhoto(name=$name, size=${bytes.size})"
}

/**
 * Puts a chosen picture where the join request can point at it.
 *
 * An interface so the auth module never learns about S3, buckets or AWS
 * signing — the app module supplies the same uploader the boards and e-mail
 * use. Null-able at the call site: a build with no storage configured simply
 * does not offer a picture, rather than offering one that cannot be saved.
 */
fun interface JoinPhotoStore {
    suspend fun store(photo: ChosenPhoto): ZillitResult<JoinPhoto>
}
