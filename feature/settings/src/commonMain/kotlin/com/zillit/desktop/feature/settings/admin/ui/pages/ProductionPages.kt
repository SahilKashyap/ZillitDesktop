package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.NameKind

/**
 * The pages about the production itself — its name, its company block, its
 * watermark, its units, and ending it.
 */

/** The production's name, everywhere in Zillit. */
@Composable
fun ProductionNamePage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.ProductionName.title,
        description = str(S.desktop_edit_project_name_detail),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        ZillitSectionCard(title = str(S.name)) {
            ZillitText(
                text = state.productionName.ifBlank { str(S.dm_gpr_not_set) },
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = str(S.desktop_name_everyone_sees),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitButton(
                text = str(S.desktop_change_name),
                onClick = { onEvent(AdminEvent.OpenProductionName) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
            )
        }
    }
}

/**
 * The company block printed at the head of the crew list.
 *
 * Shown as what it is — a preview of the header — rather than as a form,
 * because that is the only place these fields are ever read. Editing opens the
 * form over it.
 */
@Suppress("LongMethod") // A header preview; every line is one printed field.
@Composable
fun CompanyDetailsPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    val company = state.company

    AdminPage(
        title = AdminDestination.CompanyDetails.title,
        description = str(S.desktop_company_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = str(S.txt_edit_details),
                onClick = { onEvent(AdminEvent.OpenCompany) },
                size = ButtonSize.Small,
            )
        },
    ) {
        ZillitSectionCard(title = str(S.desktop_crew_list_header)) {
            if (company.name.isBlank() && company.address.isBlank()) {
                ZillitText(
                    text = str(S.desktop_no_company_header),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitText(
                    text = company.name.ifBlank { str(S.desktop_unnamed_company) },
                    style = ZillitTheme.typography.titleMedium,
                )
                HeaderLine(str(S.address), company.address)
                HeaderLine(
                    str(S.phone),
                    listOf(company.countryCode, company.phone)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                )
                HeaderLine(str(S.email), company.email)
                HeaderLine(str(S.company_number), company.companyNumber)
                HeaderLine(str(S.company_registered_address), company.registeredAddress)
                company.customFields.forEach { field -> HeaderLine(field.label, field.value) }
            }
        }

        ZillitSectionCard(title = str(S.desktop_logo)) {
            if (company.hasLogo) {
                ZillitText(
                    text = str(S.desktop_logo_is_set),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                RemoveButton(str(S.desktop_remove_logo)) {
                    onEvent(AdminEvent.Ask(AdminConfirmation.ClearCompanyLogo()))
                }
            } else {
                // Honest about the gap rather than offering a button that
                // cannot work: uploading needs the picker and the media
                // pipeline, which the phone and web clients have and this one
                // does not yet.
                ZillitNotice(
                    text = str(S.desktop_no_logo_set),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                )
            }
        }
    }
}

@Composable
private fun HeaderLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
    }
}

/** The logo stamped across documents this production sends out. */
@Composable
fun WatermarkPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Watermark.title,
        description = str(S.desktop_watermark_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        ZillitSectionCard(title = str(S.desktop_current_watermark)) {
            if (state.watermarkUrl.isNullOrBlank()) {
                ZillitText(
                    text = str(S.desktop_no_watermark),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitText(
                    text = str(S.desktop_watermark_is_set),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                RemoveButton(str(S.desktop_remove_watermark)) {
                    onEvent(AdminEvent.Ask(AdminConfirmation.ClearWatermark()))
                }
            }
        }

        ZillitNotice(
            text = str(S.desktop_watermark_upload_elsewhere),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )
    }
}

/**
 * Units — the three kinds, one page each.
 *
 * One composable, because the row is identical and only the verbs differ: a
 * shooting unit can be switched off, a joined unit renamed, a remote unit
 * neither. Those differences come from [UnitKind] rather than from three copies
 * of this list.
 */
@Composable
fun UnitsPage(
    destination: AdminDestination,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
) {
    val kind = state.unitKind ?: UnitKind.Home

    AdminPage(
        title = destination.title,
        description = when (kind) {
            // Not shooting units. `home/unit` is the dashboard — bulletin,
            // calendar, call sheet — and calling them units is the server's
            // word, not the reader's.
            UnitKind.Home -> str(S.desktop_home_units_page_description)
            UnitKind.Remote -> str(S.desktop_remote_units_detail)
            UnitKind.Shooting -> str(S.desktop_shooting_units_detail)
        },
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.desktop_search_units),
        action = {
            ZillitButton(
                text = str(S.desktop_new_unit),
                onClick = { onEvent(AdminEvent.OpenName(NameKind.Unit)) },
                size = ButtonSize.Small,
            )
        },
    ) {
        RowCard {
            val rows = state.unitsMatching
            when {
                rows.isEmpty() && state.hasLoaded && state.query.isNotBlank() ->
                    EmptyRow(str(S.desktop_no_unit_matches, state.query))

                rows.isEmpty() && state.hasLoaded ->
                    EmptyRow(str(S.desktop_no_units_yet, destination.title.lowercase()))

                else -> rows.forEachIndexed { index, unit ->
                    if (index > 0) RowRule()
                    UnitRow(unit, onEvent)
                }
            }
        }

        if (kind == UnitKind.Remote) {
            // Not an omission on this client. The server offers no update or
            // delete route for remote units, and neither does any other client.
            ZillitNotice(
                text = str(S.desktop_remote_units_fixed),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }
    }
}

@Composable
private fun UnitRow(unit: AdminUnit, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = unit.name.localised(),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )

        if (unit.locked) ZillitTag(str(S.desktop_built_in), tone = TagTone.Neutral)

        // Only the dashboard sections have a switch — `home/unit/visibility`.
        // The other two kinds have no such route.
        if (unit.kind == UnitKind.Home) {
            ZillitCheckbox(
                checked = unit.enabled,
                onCheckedChange = { onEvent(AdminEvent.UnitEnabledChanged(unit.id, it)) },
                label = str(S.desktop_in_use),
            )
        }

        if (unit.kind != UnitKind.Remote && unit.isRemovable) {
            ZillitButton(
                text = str(S.rename),
                onClick = {
                    onEvent(
                        AdminEvent.OpenName(
                            kind = NameKind.Unit,
                            targetId = unit.id,
                            initial = unit.name.localised(),
                        ),
                    )
                },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
            )
            RemoveButton(str(S.delete)) {
                onEvent(
                    AdminEvent.Ask(
                        AdminConfirmation.RemoveUnit(unit.kind, unit.id, unit.name.localised()),
                    ),
                )
            }
        }
    }
}

/**
 * Ending the production.
 *
 * Never immediate: the delay is the safety, and the production stays visible
 * with a way to call it off until it elapses. The three delays are the choice
 * — there is no separate confirm step on top, because picking "48 hours" *is*
 * the deliberate act, and the confirmation names what goes.
 */
@Suppress("LongMethod") // One page, and the wording is most of it.
@Composable
fun DeleteProductionPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.DeleteProduction.title,
        description = str(S.desktop_delete_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        if (state.deletion.isScheduled) {
            ZillitNotice(
                text = state.deletion.hours
                    ?.let { str(S.desktop_scheduled_for_deletion_in_hours, it) }
                    ?: str(S.desktop_scheduled_for_deletion),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = str(S.desktop_call_it_off),
                        onClick = { onEvent(AdminEvent.CancelDeletion) },
                        size = ButtonSize.Small,
                        enabled = !state.isSaving,
                    )
                },
            )
            return@AdminPage
        }

        ZillitSectionCard(title = str(S.desktop_what_goes)) {
            ZillitText(
                text = str(
                    S.desktop_what_goes_body,
                    state.productionName.ifBlank { str(S.desktop_this_production_lower) },
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = str(S.desktop_deletion_scheduled_not_immediate),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        ZillitSectionCard(title = str(S.desktop_schedule_it)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                DeletionSchedule.OFFERED_HOURS.forEach { hours ->
                    ZillitButton(
                        text = str(S.desktop_in_n_hours, hours),
                        onClick = {
                            onEvent(
                                AdminEvent.Ask(
                                    AdminConfirmation.DeleteProduction(
                                        hours = hours,
                                        name = state.productionName.ifBlank { str(S.desktop_this_project) },
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.isSaving,
                    )
                }
            }
        }
    }
}
