package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import com.zillit.desktop.feature.scriptsupervision.domain.SsPickedFile
import kotlinx.coroutines.launch

/**
 * Adding a photo — the web's `PhotoField`, as the four screens that keep
 * photos use it.
 *
 * ## The two rules this encodes
 *
 * **One upload at a time.** Every one of these screens keeps its photos as an
 * ARRAY on a record, so adding one is a read-modify-write of that array. Two
 * uploads finishing together would each write the array they read, and the
 * second would drop the first one's key. The web says so in `PhotoField`'s own
 * comment; [busy] is how that is enforced here.
 *
 * **The folder matters.** `/upload/file/{projectId}` reads a `folder` field
 * beside the file, and the five this module uses are the web's own names:
 * `characters`, `looks`, `garments`, `props`, `continuity-photos`. A file
 * uploaded without it lands somewhere nobody looks for it.
 *
 * A pasted address is kept beside the upload rather than replaced by it: the
 * web offers both, and a photo that already lives somewhere does not need
 * copying into project storage.
 */
@Composable
fun AddPhotoRow(
    folder: String,
    onAdded: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    // -1 is "not uploading"; 0..100 while one is in flight.
    var percent by remember { mutableStateOf(-1) }
    var link by remember { mutableStateOf("") }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            // The percentage replaces the label while it climbs, so one
            // control says both what it does and how far it has got.
            text = if (percent >= 0) "$percent%" else t("add_photo"),
            onClick = {
                ctx.whenPost {
                    ctx.scope.launch {
                        busy = true
                        percent = 0
                        // Cancelling the chooser is not a failure and is not
                        // toasted; `pickPhoto` answers null for it.
                        ctx.pickPhoto(folder) { percent = it }?.let(onAdded)
                        percent = -1
                        busy = false
                    }
                }
            },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Upload,
            loading = busy,
            enabled = enabled && !busy,
        )
        ZillitTextField(
            value = link,
            onValueChange = { link = it },
            label = null,
            modifier = Modifier.width(LINK_WIDTH),
            placeholder = PHOTO_URL_HINT,
            singleLine = true,
            enabled = enabled && !busy,
        )
        ZillitButton(
            text = t("add_image_url"),
            onClick = {
                onAdded(link.trim())
                link = ""
            },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Link,
            // The web's own test: an http(s) address with no spaces in it.
            enabled = enabled && !busy && isPhotoLink(link),
        )
    }
}

/** The web's `isLink`: an `http(s)` address and nothing else. */
internal fun isPhotoLink(text: String): Boolean = PHOTO_LINK.matches(text.trim())

private val PHOTO_LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/**
 * Picks one picture and uploads it, answering the storage key the record
 * keeps — or null when the viewer cancelled or the upload was refused.
 *
 * The just-uploaded bytes are put straight into the decoded-photo cache, so
 * the thumbnail appears without a round trip. That is not only speed: the key
 * may not be servable the instant the upload answers, and without this the
 * new photo would show as a placeholder until something re-signed it. The web
 * does the same thing with `rememberUpload`.
 */
suspend fun SsCtx.pickPhoto(folder: String, onProgress: (Int) -> Unit = {}): String? {
    // Cancelled, or no production open: nothing happened, and nothing is said.
    val picked = host.pick(IMAGE_EXTENSIONS) ?: return null
    val project = api.project() ?: return null
    val key = uploadPhoto(project, folder, picked, onProgress) ?: return null
    rememberUploadedPhoto(key, picked.bytes)
    return key
}

/** The upload itself: the storage key, or null once its refusal has been toasted. */
private suspend fun SsCtx.uploadPhoto(
    project: String,
    folder: String,
    picked: SsPickedFile,
    onProgress: (Int) -> Unit,
): String? {
    val answer = write {
        val uploaded = host.upload(
            url = api.url("/upload/file/$project"),
            file = picked,
            fields = mapOf("folder" to folder),
            onProgress = onProgress,
        )
        when (uploaded) {
            is ZillitResult.Failure -> ZillitResult.Failure(uploaded.error)
            is ZillitResult.Success -> ZillitResult.Success(Answer(uploaded.data, null))
        }
    }
    return answer?.rec?.str("key")?.takeIf { it.isNotBlank() }
}

/** What the chooser offers: the picture formats this tool's photos are. */
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "webp")

private const val PHOTO_URL_HINT = "https://…/front.jpg"
private val LINK_WIDTH = 240.dp
