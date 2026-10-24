package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import kotlinx.coroutines.launch

/**
 * The module's delete confirm — the web's `useConfirmDelete`.
 *
 * One dialog rather than one per screen, and the shape it takes is the web's:
 * the title names what is going, the body names which one, the confirm is
 * danger-red, and the OUTCOME IS THE SERVER'S — a delete that the service
 * refuses (a character still in a scene, a box with items in it) toasts its
 * own reason and leaves the row alone. [onDone] runs only on a success, which
 * is what lets a detail screen use this to leave the page.
 */
@Composable
fun DeleteDialog(
    title: String,
    name: String,
    onClose: () -> Unit,
    onDone: () -> Unit = {},
    run: suspend () -> ZillitResult<Answer>,
) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = title,
        onDismiss = onClose,
        confirmLabel = t("delete"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write { run() }
                busy = false
                if (done != null) {
                    onClose()
                    onDone()
                }
            }
        },
        busy = busy,
        danger = true,
        width = CONFIRM_WIDTH,
    ) {
        if (name.isNotBlank()) ZillitText(name)
    }
}

private val CONFIRM_WIDTH = 440.dp

/**
 * A yes/no the caller answers itself — discarding an edit, removing a section.
 *
 * Unlike [DeleteDialog] nothing is sent: the outcome is local, so there is no
 * server message to carry and [onConfirm] simply runs.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = false,
) {
    FormDialog(
        open = true,
        title = title,
        onDismiss = onClose,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        danger = danger,
    ) {
        MutedText(message, maxLines = MESSAGE_LINES)
    }
}

private const val MESSAGE_LINES = 4
