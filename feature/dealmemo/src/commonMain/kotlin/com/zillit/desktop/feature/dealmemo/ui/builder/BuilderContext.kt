package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadContext
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadUser
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState

/** What `toDealMemoPayload` reads besides the form, from the tool's state. */
internal fun payloadContext(ui: DealMemoUiState, builder: BuilderState): PayloadContext {
    val settings = ui.projectSettings.view
    return PayloadContext(
        selectedUnion = builder.reference.selectedUnion,
        resolvedRate = builder.reference.resolvedRate,
        departments = ui.catalogue,
        usersById = ui.crewDirectory.orEmpty().associate {
            it.userId to PayloadUser(it.departmentId, it.designationId)
        },
        payrollDefaults = settings.payrollDefaults,
        projectDayTypes = settings.dayTypes,
        nonUnionPaybreakdown = settings.nonUnionPaybreakdown,
        coaCodes = ui.coa.accounts.map { it.code }.toSet().takeIf { ui.coa.loaded },
    )
}
