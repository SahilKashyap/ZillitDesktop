package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.SsPickedFile
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * Choose a file, see what the service parses out of it, then import — the
 * web's `FileImport`, which both import screens are built on.
 *
 * ## Why the preview matters enough to be its own step
 *
 * The import REWRITES the production's scene list. The service offers a dry
 * run for exactly that reason (`/preview` on both routes: same multipart
 * call, nothing stored), and the web makes it a separate button rather than
 * importing straight away. Kept, and the import is behind a confirm as well.
 *
 * ## What the desktop does differently
 *
 * The web has a drag-and-drop target; this is a Choose-file button, because
 * the file has to come through the host's chooser ([SsHost.pick]) — a feature
 * module cannot open a window. The two calls are the same either way.
 */
@Composable
internal fun FileImportScreen(
    title: String,
    subtitle: String,
    formats: List<Pair<String, String>>,
    extensions: Set<String>,
    previewPath: String,
    importPath: String,
    confirmTitle: String,
    confirmText: String,
    onImported: () -> Unit = {},
    actions: (@Composable (ImportState) -> Unit)? = null,
    renderPreview: @Composable ColumnScope.(Rec) -> Unit,
    renderResult: @Composable ColumnScope.(Rec) -> Unit,
) {
    val ctx = LocalSs.current
    val state = remember(previewPath) { ImportState() }
    var confirming by remember(previewPath) { mutableStateOf(false) }

    SsPageHeader(title = title, sub = subtitle)

    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("supported_formats")) {
        formats.forEach { (extension, hintKey) ->
            MutedText("$extension — ${t(hintKey)}")
        }
        ImportControls(
            state = state,
            extensions = extensions,
            previewPath = previewPath,
            onConfirm = { confirming = true },
            actions = actions,
        )
        state.file?.let { MutedText(it.name) }
    }

    // The result first, then the preview — the web's order, and the one that
    // reads right: what just happened is above what might happen next.
    state.result?.let { Column(Modifier.fillMaxWidth()) { renderResult(it) } }
    state.parsed?.let { Column(Modifier.fillMaxWidth()) { renderPreview(it) } }

    if (confirming) {
        FormDialog(
            open = true,
            title = confirmTitle,
            onDismiss = { confirming = false },
            confirmLabel = t("import"),
            onConfirm = {
                confirming = false
                ctx.whenPost {
                    ctx.scope.launch {
                        state.run(ctx, importPath, preview = false)
                        if (state.result != null) onImported()
                    }
                }
            },
            width = CONFIRM_WIDTH,
        ) {
            ZillitText(confirmText)
        }
    }
}

/** Choose, preview, import — and whatever the screen adds beside them. */
@Composable
private fun ImportControls(
    state: ImportState,
    extensions: Set<String>,
    previewPath: String,
    onConfirm: () -> Unit,
    actions: (@Composable (ImportState) -> Unit)?,
) {
    val ctx = LocalSs.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = state.file?.name ?: t("choose_a_file"),
            onClick = {
                ctx.whenPost {
                    ctx.scope.launch {
                        // Null is "cancelled": not a failure, so nothing is
                        // toasted and whatever was chosen before stays.
                        ctx.host.pick(extensions)?.let(state::chose)
                    }
                }
            },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Upload,
            enabled = !state.busy,
        )
        ZillitButton(
            text = t("preview"),
            onClick = { ctx.scope.launch { state.run(ctx, previewPath, preview = true) } },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Eye,
            loading = state.running == "preview",
            enabled = state.file != null && !state.busy,
        )
        ZillitButton(
            text = t("import_to_project"),
            onClick = onConfirm,
            leadingIcon = ZillitIcons.Upload,
            loading = state.running == "import",
            enabled = state.file != null && !state.busy,
        )
        actions?.invoke(state)
    }
}

/**
 * The chosen file and what the two calls answered.
 *
 * One object because the resets are the point: choosing another file has to
 * drop the previous preview AND the previous result, or the screen shows one
 * file's name over another file's parse.
 */
@Stable
internal class ImportState {

    var file by mutableStateOf<SsPickedFile?>(null)
        private set

    /** What the dry run parsed out of the file; null until Preview is pressed. */
    var parsed by mutableStateOf<Rec?>(null)
        private set

    /** What the import actually wrote. */
    var result by mutableStateOf<Rec?>(null)
        private set

    /** "preview" or "import" while that call is in flight, else blank. */
    var running by mutableStateOf("")
        private set

    val busy: Boolean get() = running.isNotBlank()

    fun chose(picked: SsPickedFile) {
        file = picked
        parsed = null
        result = null
    }

    /**
     * Runs one of the two calls.
     *
     * A successful IMPORT clears the file and the preview: the scenes are in
     * the production now, and leaving the file loaded invites importing it
     * twice. A successful preview leaves everything, because the next press
     * is meant to be Import.
     */
    suspend fun run(ctx: SsCtx, path: String, preview: Boolean) {
        val picked = file ?: return
        if (busy) return
        running = if (preview) "preview" else "import"
        val answer = ctx.write { ctx.upload(path, picked) }
        running = ""
        val record = answer?.rec ?: return
        if (preview) {
            parsed = record
        } else {
            result = record
            file = null
            parsed = null
        }
    }
}

private val CONFIRM_WIDTH = 480.dp
