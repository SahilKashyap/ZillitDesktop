package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.scriptsupervision.domain.Breakdown
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tagging a scene with an element — the web's `BreakdownTagModal`.
 *
 * ⚠ The endpoint FINDS OR CREATES the element: `POST /breakdown/tag` takes a
 * category and an element NAME, not an element id, and makes the element if
 * the production has no such name yet. That is why the name field offers the
 * category's existing elements but does not restrict to them — typing a new
 * name is how a new element is born, and picking an old one is how the
 * vocabulary stays small.
 *
 * The tagged text defaults to the element's name, as the web's does, and
 * [Breakdown.locate] finds where it sits in the script so the tag points at
 * the same place a tag made on the web would.
 */
@Suppress("LongMethod") // One form, its three fields and the reminder line.
@Composable
internal fun AddTagDialog(
    sceneId: String,
    categories: List<Rec>,
    scriptText: List<String>,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    var slug by remember { mutableStateOf("") }
    var elementName by remember { mutableStateOf("") }
    var taggedText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // The category's existing elements, so the vocabulary stays small.
    val existing = rememberCategoryElements(slug)

    FormDialog(
        open = true,
        title = t("add_tag"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val name = elementName.trim()
                // The web's default: the text tagged IS the element's name
                // unless something else was typed.
                val text = taggedText.trim().ifBlank { name }
                val at = Breakdown.locate(scriptText, text)
                val done = ctx.write {
                    ctx.api.post(
                        "/breakdown/tag/${ctx.api.project().orEmpty()}/$sceneId",
                        body(
                            "category_slug" to slug,
                            "element_name" to name,
                            "tagged_text" to text,
                            "line_index" to at.lineIndex,
                            "char_start" to at.charStart,
                            "char_end" to at.charEnd,
                        ),
                    )
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = slug.isNotBlank() && elementName.isNotBlank(),
        busy = saving,
        width = DIALOG_WIDTH,
    ) {
        FormGrid {
            PickInput(
                value = slug,
                options = categories.map { it.str("slug") to it.str("name") },
                onChange = { slug = it },
                label = t("category"),
            )
            TextInput(
                value = elementName,
                onChange = { elementName = it },
                label = t("element"),
                modifier = FormWide,
                help = t("element_help"),
                enabled = slug.isNotBlank(),
            )
            TextInput(
                value = taggedText,
                onChange = { taggedText = it },
                label = t("tagged_text"),
                modifier = FormWide,
                help = t("tagged_text_help"),
            )
        }
        // Not a picker: the name must stay free text so a new element can be
        // typed. These are a reminder of what the category already has.
        if (existing.isNotEmpty()) {
            MutedText(existing.take(SUGGESTION_LIMIT).joinToString(", ") { it.str("name") })
        }
    }
}

/** The elements the picked category already has, for the reminder line. */
@Composable
private fun rememberCategoryElements(slug: String): List<Rec> {
    val ctx = LocalSs.current
    var rows by remember(slug) { mutableStateOf(emptyList<Rec>()) }
    LaunchedEffect(slug) {
        if (slug.isBlank()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        val project = ctx.api.project().orEmpty()
        val answer = ctx.api.get("/breakdown/elements/$project", mapOf("category_slug" to slug))
        rows = (answer as? ZillitResult.Success)?.data?.rows.orEmpty()
    }
    return rows
}

/**
 * Accepting or rejecting the suggested tags — the web's
 * `BreakdownReviewModal`.
 *
 * ⚠ A tag left UNDECIDED stays suggested: the call carries only the
 * decisions made, so closing the dialog having ticked nothing changes
 * nothing. That is deliberate on the web and worth keeping — a reviewer who
 * is unsure about one tag should not have it silently rejected by saving the
 * others.
 */
@Suppress("LongMethod") // The bulk buttons, the list and the save.
@Composable
internal fun ReviewSuggestionsDialog(
    sceneId: String,
    suggestions: List<Rec>,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    var decisions by remember(sceneId) { mutableStateOf(emptyMap<String, String>()) }
    var saving by remember { mutableStateOf(false) }
    val decided = decisions.filterValues { it.isNotBlank() }

    FormDialog(
        open = true,
        title = t("review_ai_suggestions"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post(
                        "/breakdown/bulk-review/${ctx.api.project().orEmpty()}/$sceneId",
                        buildJsonObject {
                            put(
                                "decisions",
                                buildJsonArray {
                                    decided.forEach { (tagId, action) ->
                                        add(
                                            buildJsonObject {
                                                put("tagId", tagId)
                                                put("action", action)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = decided.isNotEmpty(),
        busy = saving,
        width = DIALOG_WIDTH,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = t("accept_all"),
                onClick = { decisions = suggestions.associate { it.id to Breakdown.CONFIRM } },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = t("reject_all"),
                onClick = { decisions = suggestions.associate { it.id to Breakdown.REJECT } },
                variant = ButtonVariant.Secondary,
            )
            MutedText("${decided.size}/${suggestions.size}")
        }
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(REVIEW_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            suggestions.forEach { tag ->
                SuggestionRow(
                    tag = tag,
                    decision = decisions[tag.id].orEmpty(),
                    onDecide = { action ->
                        // Pressing the same button again un-decides it, so a
                        // mis-click does not have to be saved.
                        decisions = decisions + (tag.id to if (decisions[tag.id] == action) "" else action)
                    },
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(tag: Rec, decision: String, onDecide: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(tag.str("element_name"), style = ZillitTheme.typography.bodySmall)
            tag.str("tagged_text").takeIf { it.isNotBlank() && it != tag.str("element_name") }
                ?.let { MutedText("“$it”") }
        }
        ZillitButton(
            text = t("accept"),
            onClick = { onDecide(Breakdown.CONFIRM) },
            variant = if (decision == Breakdown.CONFIRM) ButtonVariant.Primary else ButtonVariant.Secondary,
        )
        ZillitButton(
            text = t("reject"),
            onClick = { onDecide(Breakdown.REJECT) },
            variant = if (decision == Breakdown.REJECT) ButtonVariant.Danger else ButtonVariant.Secondary,
        )
    }
}

private const val SUGGESTION_LIMIT = 12
private val DIALOG_WIDTH = 600.dp
private val REVIEW_HEIGHT = 320.dp
