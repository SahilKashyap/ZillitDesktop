package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateForm
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The template as the log will show it, to try out — the web's
 * `TestDriveModal`.
 *
 * ⚠ Nothing here is saved, and nothing is uploaded: it is the real form with
 * the real defaults, filled in and thrown away. That is the point — a
 * designer finds a field in the wrong place by using it, not by reading it.
 */
@Composable
internal fun TestDriveDialog(sections: List<TemplateSection>, onClose: () -> Unit) {
    var values by remember { mutableStateOf(TemplateForm.templateDefaults(sections)) }
    FormDialog(
        open = true,
        title = t("test_drive"),
        onDismiss = onClose,
        confirmLabel = t("close"),
        onConfirm = onClose,
        width = DIALOG_WIDTH,
    ) {
        if (sections.isEmpty()) {
            MutedText(t("no_sections_enabled"))
            return@FormDialog
        }
        sections.forEach { section ->
            TemplateSectionCard(
                section = section,
                values = values[section.key].orEmpty(),
                onValue = { key, value ->
                    values = values + (section.key to (values[section.key].orEmpty() + (key to value)))
                },
                accent = Templates.sectionColour(section),
            )
        }
    }
}

/**
 * A template's earlier versions — the web's `VersionHistoryDrawer`.
 *
 * ⚠ Newest FIRST, which is the reverse of how the service stores them: it
 * appends, so the list arrives oldest first and a reader looking for "what it
 * was before I broke it" would otherwise have to scroll to the bottom.
 *
 * Restoring keeps the current sections as a version first, so nothing is
 * lost — that is the service's own behaviour on a PUT that carries
 * `sections`, and it is why this needs no confirmation of its own beyond the
 * one it asks.
 */
@Composable
internal fun VersionHistoryDialog(
    templateId: String,
    history: List<Rec>,
    onClose: () -> Unit,
    onRestored: (Rec) -> Unit,
) {
    val ctx = LocalSs.current
    var restoring by remember { mutableStateOf<Rec?>(null) }
    FormDialog(
        open = true,
        title = t("version_history"),
        onDismiss = onClose,
        confirmLabel = t("close"),
        onConfirm = onClose,
        width = DIALOG_WIDTH,
    ) {
        if (history.isEmpty()) {
            MutedText(t("no_versions"))
            return@FormDialog
        }
        history.reversed().forEach { entry ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText("v${entry.int("version")}", style = ZillitTheme.typography.numeric)
                    MutedText(entry.str("updated_at"))
                    MutedText(
                        t(
                            "sections_count",
                            "count" to Templates.loadSections(entry).count { it.enabled },
                        ),
                    )
                }
                ZillitButton(
                    text = t("restore"),
                    onClick = { restoring = entry },
                    variant = ButtonVariant.Secondary,
                )
            }
        }
    }

    restoring?.let { entry ->
        ConfirmRestore(entry, templateId, onClose = { restoring = null }, onRestored = onRestored)
    }
}

/** Putting a version back, confirmed first. */
@Composable
private fun ConfirmRestore(entry: Rec, templateId: String, onClose: () -> Unit, onRestored: (Rec) -> Unit) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("restore_version_title", "version" to entry.int("version")),
        onDismiss = onClose,
        confirmLabel = t("restore"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post("/continuity/templates/$templateId/restore/${entry.int("version")}")
                }
                busy = false
                if (done != null) {
                    onClose()
                    done.rec?.let(onRestored)
                }
            }
        },
        busy = busy,
    ) {
        MutedText(t("restore_version_hint"))
    }
}

private val DIALOG_WIDTH = 720.dp
