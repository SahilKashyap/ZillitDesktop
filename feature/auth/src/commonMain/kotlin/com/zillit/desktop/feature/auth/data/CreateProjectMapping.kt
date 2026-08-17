package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.resolvedSubType

/**
 * Draft → wire payload.
 *
 * A separate file so the mapping is testable without the repository, and
 * because it carries the two rules that are easy to get wrong: the "add new"
 * sentinel must be replaced by the typed value, and the phone pair must be sent
 * whole or not at all.
 */
internal fun NewProductionDraft.toCreateDto(
    selectedType: ProductionType?,
    confirmCode: String,
    languageName: String? = null,
) = CreateProjectDto(
    projectName = productionName.trim(),
    projectTypeId = typeId.orEmpty(),
    // The display label the server stores alongside the id.
    projectType = selectedType?.label,
    projectSubType = resolvedSubType(selectedType),
    email = email.trim(),
    firstName = firstName.trim(),
    lastName = lastName.trim(),
    projectLanguage = languageCode,
    projectLanguageDescription = languageName,
    enterpriseClientCode = storageCode.trim().takeIf { it.isNotEmpty() },
    confirmCode = confirmCode,
    countryCode = countryCode.trim().takeIf { it.isNotEmpty() && phone.isNotBlank() },
    phone = phone.trim().takeIf { it.isNotEmpty() && countryCode.isNotBlank() },
)
