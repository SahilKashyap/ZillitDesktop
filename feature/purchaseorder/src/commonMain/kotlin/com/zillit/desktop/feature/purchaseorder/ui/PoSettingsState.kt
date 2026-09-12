package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoNominal
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember

/** The Settings tab — the web's `POSettings`, with a dirty flag and a Save per card. */
data class PoSettingsState(
    val loading: Boolean = false,
    val loadError: ZillitError? = null,
    val saved: PoSettings = PoSettings(),
    val edited: PoSettings = PoSettings(),
    val savedRules: List<PoAssignmentRule> = emptyList(),
    val rules: List<PoAssignmentRule> = emptyList(),
    val rulesLoading: Boolean = false,
    /** Cards mid-save. */
    val saving: Set<PoSettingsSection> = emptySet(),
    /** Cards whose Save just landed; the tick fades after `PoSettingsActions.SAVED_FLASH_MILLIS`. */
    val justSaved: Set<PoSettingsSection> = emptySet(),
    /** Whether the host wired a picker and storage for the terms document. */
    val canAttachTerms: Boolean = false,
    val termsUploading: Boolean = false,
    /** Under the terms block, where the web shows its refusals and failures; cleared on the next attempt. */
    val termsError: String? = null,
    val team: List<PoTeamMember> = emptyList(),
    val departments: List<PoDepartment> = emptyList(),
    val nominals: List<PoNominal> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    /** Whether [section] differs from what the server holds — the web's per-card dirty flags. */
    fun isDirty(section: PoSettingsSection): Boolean = when (section) {
        PoSettingsSection.Description -> edited.descriptionFormat != saved.descriptionFormat
        PoSettingsSection.Rental ->
            edited.autoSplitRentals != saved.autoSplitRentals || edited.splitType != saved.splitType
        PoSettingsSection.Numbering ->
            edited.numberPrefix != saved.numberPrefix ||
                edited.allowAmendAfterApproval != saved.allowAmendAfterApproval
        PoSettingsSection.Asset -> edited.assetFilters != saved.assetFilters
        PoSettingsSection.Rules -> rules != savedRules
    }
}

/** The five cards that save on their own. */
enum class PoSettingsSection { Description, Rental, Numbering, Asset, Rules }

/** [from]'s values for [section], everything else as it is. */
internal fun PoSettings.taking(section: PoSettingsSection, from: PoSettings): PoSettings = when (section) {
    PoSettingsSection.Description -> copy(descriptionFormat = from.descriptionFormat)
    PoSettingsSection.Rental -> copy(autoSplitRentals = from.autoSplitRentals, splitType = from.splitType)
    PoSettingsSection.Numbering ->
        copy(numberPrefix = from.numberPrefix, allowAmendAfterApproval = from.allowAmendAfterApproval)
    PoSettingsSection.Asset -> copy(assetFilters = from.assetFilters)
    PoSettingsSection.Rules -> this
}
