@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")
// One page, three settings; its branches are the "has this field changed" checks
// that decide whether Save is live.

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.domain.limitsFromInputs

/**
 * A production's settings for the tool (posting users):
 *   - who may browse the gallery: public photos only (the default — a
 *     discarded photo should not circulate among the crew), or everything;
 *   - the discard allowance a newly enrolled member starts with;
 *   - how sure the system must be before it names a face by itself, and when
 *     it only proposes a name for a person to confirm;
 *   - deleting all of the production's face data (project admins).
 *
 * Changing the matching numbers decides every photo's automatic names again
 * from what was already found — no photo is looked at a second time.
 */
@Composable
internal fun SettingsPage(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val form = state.settings
    val stored = state.me.settings
    val limits = limitsFromInputs(form.limits)
    val numbersOk = form.numbersOk()

    val dirty = form.scope != stored.viewerScope ||
        (limits != null && limits != stored.defaultDiscardLimits) ||
        (numbersOk && !sameNumbers(form, stored))

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1280.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SPageHead(str(S.settings), str(S.desktop_stk_settings_lede))

            SCard(Modifier.widthIn(max = 760.dp)) {
                // Who can browse the gallery.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(str(S.desktop_stk_settings_scope), 13, color = k.muted)
                    SCheck(
                        checked = form.scope == ViewerScope.Cleared,
                        label = str(S.desktop_stk_settings_scope_cleared),
                        onPick = { onEvent(StillsEvent.SettingsScope(ViewerScope.Cleared)) },
                        hint = str(S.desktop_stk_settings_scope_cleared_hint),
                        enabled = !form.busy,
                    )
                    SCheck(
                        checked = form.scope == ViewerScope.All,
                        label = str(S.desktop_stk_review_tab_all),
                        onPick = { onEvent(StillsEvent.SettingsScope(ViewerScope.All)) },
                        hint = str(S.desktop_stk_settings_scope_all_hint),
                        enabled = !form.busy,
                    )
                }

                SField(
                    label = str(S.desktop_stk_settings_default_limits),
                    modifier = Modifier.padding(top = 14.dp),
                    hint = str(S.desktop_stk_settings_default_limits_hint),
                    warn = if (limits == null) str(S.desktop_stk_limits_invalid) else null,
                ) {
                    SDiscardLimits(form.limits, { onEvent(StillsEvent.SettingsLimits(it)) }, enabled = !form.busy)
                }

                // The three matching numbers, laid out as the allowance is.
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SText(str(S.desktop_stk_settings_matching), 13, color = k.muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple("auto", form.auto, S.desktop_stk_settings_auto to S.desktop_stk_settings_auto_hint),
                            Triple("suggest", form.suggest, S.desktop_stk_settings_suggest to S.desktop_stk_settings_suggest_hint),
                            Triple("margin", form.margin, S.desktop_stk_settings_margin to S.desktop_stk_settings_margin_hint),
                        ).forEach { (which, value, words) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                SText(str(words.first), 12, color = k.muted, maxLines = 2)
                                SInput(
                                    value = value,
                                    onChange = { onEvent(StillsEvent.SettingsNumber(which, it)) },
                                    enabled = !form.busy,
                                    size = 14,
                                    background = k.bg,
                                )
                                SText(str(words.second), 11, color = k.muted)
                            }
                        }
                    }
                    SHint(str(S.desktop_stk_settings_matching_hint))
                    if (!numbersOk) SHint(str(S.desktop_stk_settings_numbers_invalid), warn = true)
                }

                SBtn(
                    text = if (form.busy) str(S.ah_saving) else str(S.save),
                    onClick = { onEvent(StillsEvent.SaveSettings) },
                    modifier = Modifier.padding(top = 14.dp),
                    kind = SBtnKind.Primary,
                    enabled = !form.busy && dirty && limits != null && numbersOk,
                )

                // Said in words first, done behind a second question.
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        SText(str(S.desktop_stk_settings_face_data), 13, Bold, k.muted)
                        SText(
                            text = if (state.isAdmin) str(S.desktop_stk_settings_face_data_hint) else str(S.desktop_stk_settings_face_data_admin_only),
                            size = 13,
                            color = k.muted,
                        )
                    }
                    SBtn(
                        text = str(S.desktop_stk_settings_face_data_delete),
                        onClick = { onEvent(StillsEvent.AskWipe(true)) },
                        small = true,
                        enabled = !form.busy && state.isAdmin,
                    )
                }
            }
        }
    }

    if (form.askingWipe) {
        SConfirmDialog(
            title = str(S.desktop_stk_settings_face_data_delete),
            message = str(S.desktop_stk_settings_face_data_confirm),
            confirmLabel = str(S.desktop_stk_settings_face_data_delete),
            busy = form.busy,
            onConfirm = { onEvent(StillsEvent.WipeFaceData) },
            onClose = { onEvent(StillsEvent.AskWipe(false)) },
        )
    }
}

private fun sameNumbers(form: SettingsFormState, stored: com.zillit.desktop.feature.selectstills.domain.StillsSettings): Boolean =
    form.auto.trim().toIntOrNull() == stored.thresholds.auto &&
        form.suggest.trim().toIntOrNull() == stored.thresholds.suggest &&
        form.margin.trim().toIntOrNull() == stored.thresholds.margin
