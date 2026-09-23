package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.CompanyCustomField
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyField
import com.zillit.desktop.feature.crewlist.domain.CompanyRules
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.ui.CompanyEditorState
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewDrawer
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.DialCodePicker
import com.zillit.desktop.feature.crewlist.ui.components.crewPalette
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The production's company details — the web's `CompanyDetails` drawer
 * (ZL-19721), reached from Customise & Preview: a sticky live preview of the
 * letterhead, then Brand logo · Company information · Addresses · Custom
 * fields, and Cancel / Save changes at the foot.
 */
@Composable
internal fun CompanyDetailsDrawer(
    editor: CompanyEditorState?,
    copy: CrewCopy,
    dialCodes: List<DialCode>,
    onEvent: (CrewListEvent) -> Unit,
) {
    val held = remember { arrayOfNulls<CompanyEditorState>(1) }
    editor?.let { held[0] = it }
    CrewDrawer(visible = editor != null, width = 720.dp, onDismiss = { onEvent(CrewListEvent.Admin.CloseCompany) }) {
        val shown = editor ?: held[0] ?: return@CrewDrawer
        LivePreview(shown, copy, onEvent)
        Box(Modifier.weight(1f).fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)) {
            when {
                shown.loading ->
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                        ZillitSpinner(size = 26.dp)
                    }
                shown.failure != null -> ZillitText(
                    text = shown.failure,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.danger,
                    modifier = Modifier.padding(24.dp),
                )
                else -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LogoCard(shown, copy, onEvent)
                    InformationCard(shown, copy, dialCodes, onEvent)
                    AddressesCard(shown, copy, onEvent)
                    CustomFieldsCard(shown, copy, onEvent)
                }
            }
        }
        DrawerFooter(shown, copy, onEvent)
    }
    RemoveLogoDialog(visible = editor?.confirmRemoveLogo == true, copy = copy, onEvent = onEvent)
}

@Composable
private fun DrawerFooter(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        ZillitButton(
            text = copy.t("Cancel", str(S.cancel)),
            variant = ButtonVariant.Secondary,
            enabled = !editor.saving,
            onClick = { onEvent(CrewListEvent.Admin.CloseCompany) },
        )
        ZillitButton(
            text = copy.t("SaveChanges", str(S.txt_save_changes)),
            leadingIcon = ZillitIcons.Save,
            loading = editor.saving,
            enabled = !editor.saving && !editor.loading,
            onClick = { onEvent(CrewListEvent.Admin.SaveCompany) },
        )
    }
}

/** The web's Popconfirm before a logo is cleared — removed from the letterhead on save. */
@Composable
private fun RemoveLogoDialog(visible: Boolean, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    ZillitDialogShell(
        title = copy.t("Areyousuretodelete", str(S.desktop_are_you_sure_to_delete)),
        icon = ZillitIcons.Trash,
        visible = visible,
        onDismiss = { onEvent(CrewListEvent.Admin.ResolveRemoveLogo(remove = false)) },
        width = 380.dp,
        actions = {
            ZillitButton(
                text = copy.t("No", str(S.no)),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CrewListEvent.Admin.ResolveRemoveLogo(remove = false)) },
            )
            ZillitButton(
                text = copy.t("Yes", str(S.yes)),
                variant = ButtonVariant.Danger,
                onClick = { onEvent(CrewListEvent.Admin.ResolveRemoveLogo(remove = true)) },
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_cl_logo_removed_on_save),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** The sticky header: the letterhead as it will read, with the extra lines folded beneath. */
@Composable
private fun LivePreview(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.accent))
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
                val heading = copy.t("CompanyDetails", str(S.company_details)) + " · " +
                    copy.t("LivePreview", str(S.desktop_live_preview))
                ZillitText(
                    text = heading.uppercase(),
                    style = eyebrow(0.06),
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = copy.t("Close", str(S.close)),
                    onClick = { onEvent(CrewListEvent.Admin.CloseCompany) },
                )
            }
            Letterhead(editor, copy)
            PreviewExtras(editor, copy, onEvent)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun Letterhead(editor: CompanyEditorState, copy: CrewCopy) {
    val colors = ZillitTheme.colors
    val details = editor.details
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LogoTile(editor.logoImage, details.initials, size = 56.dp, dashed = false)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = details.name.ifBlank { copy.t("CompanyName", str(S.company_name)) },
                style = ZillitTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = if (details.name.isBlank()) colors.textMuted else colors.textPrimary,
                maxLines = 1,
            )
            val lines = listOf(
                details.address,
                listOf(details.phoneLine, details.email).filter { it.isNotBlank() }.joinToString("  •  "),
            )
            lines.filter { it.isNotBlank() }.forEach { line ->
                ZillitText(line, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 2)
            }
        }
    }
}

/** Company number, registered address and every custom field — folded, as the web folds them. */
@Composable
private fun PreviewExtras(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val details = editor.details
    val extras = buildList {
        if (details.number.isNotBlank()) add(copy.t("CompanyNumber", str(S.desktop_company_no)) to details.number)
        if (details.registeredAddress.isNotBlank()) {
            add(copy.t("CompanyRegisteredAddress", str(S.company_registered_address)) to details.registeredAddress)
        }
        details.customFields.filter { it.label.isNotBlank() }.forEach { add(it.label to it.value) }
    }
    if (extras.isEmpty()) return
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(colors.divider))
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable { onEvent(CrewListEvent.Admin.ToggleCompanyDetails) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(
            icon = ZillitIcons.ChevronDown,
            tint = colors.textMuted,
            size = 12.dp,
            modifier = Modifier.rotate(if (editor.detailsExpanded) 0f else -90f),
        )
        ZillitText(copy.t("Details", str(S.details)).uppercase(), style = eyebrow(0.05), color = colors.textMuted)
        CountPill(extras.size)
    }
    if (!editor.detailsExpanded) return
    extras.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            pair.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = label.uppercase(),
                        style = eyebrow(0.0).copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = value.ifBlank { "—" },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                    )
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LogoCard(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    SectionCard(copy.t("BrandLogo", str(S.desktop_brand_logo))) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LogoTile(editor.logoImage, editor.details.initials, size = 84.dp, dashed = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitText(
                    text = if (editor.hasLogo) {
                        copy.t("LogoUploaded", str(S.desktop_logo_uploaded))
                    } else {
                        copy.t("NoLogoYet", str(S.desktop_no_logo_yet))
                    },
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                ZillitText(
                    text = copy.t("LogoSpecHint", str(S.desktop_cl_logo_spec_hint)),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val upload = when {
                        editor.hasLogo -> copy.t("Replace", str(S.replace))
                        else -> copy.t("UploadLogo", str(S.desktop_upload_logo))
                    }
                    ZillitButton(
                        text = upload,
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Upload,
                        onClick = { onEvent(CrewListEvent.Admin.PickLogo) },
                    )
                    if (editor.hasLogo) {
                        ZillitButton(
                            text = copy.t("Remove", str(S.remove)),
                            variant = ButtonVariant.Danger,
                            leadingIcon = ZillitIcons.Trash,
                            onClick = { onEvent(CrewListEvent.Admin.AskRemoveLogo) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InformationCard(
    editor: CompanyEditorState,
    copy: CrewCopy,
    dialCodes: List<DialCode>,
    onEvent: (CrewListEvent) -> Unit,
) {
    val details = editor.details
    val edit = { changed: CompanyDetails -> onEvent(CrewListEvent.Admin.EditCompany(changed)) }
    SectionCard(copy.t("CompanyInformation", str(S.desktop_company_information))) {
        ZillitTextField(
            value = details.name,
            onValueChange = { edit(details.copy(name = it)) },
            label = copy.t("CompanyName", str(S.company_name)),
            placeholder = copy.t("CompanyNamePlaceholder", str(S.desktop_cl_company_name_placeholder)),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ZillitTextField(
                value = details.number,
                onValueChange = { edit(details.copy(number = it)) },
                label = copy.t("CompanyNumber", str(S.desktop_company_no)),
                placeholder = copy.t("CompanyNumber", str(S.desktop_company_no)),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = details.email,
                onValueChange = { edit(details.copy(email = it)) },
                label = copy.t("CompanyEmail", str(S.company_email)),
                placeholder = "office@company.com",
                errorText = editor.problems.firstOrNull { it.field == CompanyField.Email }?.message,
                modifier = Modifier.weight(1f),
            )
        }
        PhoneFields(editor, copy, dialCodes, edit)
    }
}

/** Dial code and number side by side — digits only, at most 20, as the web's field trims them. */
@Composable
private fun PhoneFields(
    editor: CompanyEditorState,
    copy: CrewCopy,
    dialCodes: List<DialCode>,
    edit: (CompanyDetails) -> Unit,
) {
    val details = editor.details
    val phoneProblem = editor.problems.firstOrNull { it.field == CompanyField.Phone }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(
            text = copy.t("Phone", str(S.phone)),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DialCodePicker(
                value = details.countryCode,
                codes = dialCodes,
                placeholder = copy.t("SelectCountrycode", str(S.code)),
                isError = phoneProblem != null && details.countryCode.isBlank(),
                onPick = { code -> edit(details.copy(countryCode = code)) },
                modifier = Modifier.width(130.dp),
                // The text field's own control height, so the pair lines up.
                height = 32.dp,
            )
            ZillitTextField(
                value = details.phone,
                onValueChange = { raw ->
                    edit(details.copy(phone = raw.filter { it in '0'..'9' }.take(CompanyRules.MAX_PHONE)))
                },
                placeholder = copy.t("Phone", str(S.phone)),
                errorText = phoneProblem?.message,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddressesCard(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val details = editor.details
    SectionCard(copy.t("Addresses", str(S.desktop_addresses))) {
        ZillitTextField(
            value = details.address,
            onValueChange = { onEvent(CrewListEvent.Admin.EditCompany(details.copy(address = it))) },
            label = copy.t("CompanyAddress", str(S.company_address)),
            placeholder = copy.t("CompanyAddress", str(S.company_address)),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = details.registeredAddress,
            onValueChange = { onEvent(CrewListEvent.Admin.EditCompany(details.copy(registeredAddress = it))) },
            label = copy.t("CompanyRegisteredAddress", str(S.company_registered_address)),
            placeholder = copy.t("CompanyRegisteredAddress", str(S.company_registered_address)),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CustomFieldsCard(editor: CompanyEditorState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val details = editor.details
    val setFields = { fields: List<CompanyCustomField> ->
        onEvent(CrewListEvent.Admin.EditCompany(details.copy(customFields = fields)))
    }
    SectionCard(copy.t("CustomFields", str(S.custom_fields)), count = details.customFields.size) {
        if (details.customFields.isEmpty()) {
            ZillitText(
                text = copy.t("NoCustomFields", str(S.desktop_no_extra_fields_yet)),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        details.customFields.forEachIndexed { index, field ->
            val labelProblem = editor.problems.firstOrNull { it.field == CompanyField.CustomLabel && it.index == index }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                ZillitTextField(
                    value = field.label,
                    onValueChange = { label ->
                        setFields(details.customFields.replaced(index) { copy(label = label) })
                    },
                    placeholder = copy.t("Label", str(S.ah_lbl_title)),
                    errorText = labelProblem?.message,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = field.value,
                    onValueChange = { value ->
                        setFields(details.customFields.replaced(index) { copy(value = value) })
                    },
                    placeholder = copy.t("Value", str(S.ah_addl_value_hint)),
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = CrewIcons.Minus,
                    contentDescription = copy.t("Remove", str(S.remove)),
                    size = 36.dp,
                    onClick = { setFields(details.customFields.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
        ZillitButton(
            text = copy.t("AddField", str(S.add_field)),
            variant = ButtonVariant.Tertiary,
            leadingIcon = ZillitIcons.Add,
            onClick = { setFields(details.customFields + CompanyCustomField()) },
        )
    }
}

private fun List<CompanyCustomField>.replaced(index: Int, change: CompanyCustomField.() -> CompanyCustomField) =
    mapIndexed { i, field -> if (i == index) field.change() else field }

/** A white card with the web's square-bullet section title and an optional count. */
@Composable
private fun SectionCard(title: String, count: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(ZillitTheme.colors.accent))
            ZillitText(
                text = title.uppercase(),
                style = eyebrow(0.06).copy(fontSize = 12.sp),
                color = ZillitTheme.colors.textSecondary,
            )
            count?.let { CountPill(it) }
        }
        content()
    }
}

@Composable
private fun CountPill(count: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** A small bold uppercase label, tracked out by [tracking] em. */
@Composable
private fun eyebrow(tracking: Double): TextStyle =
    ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = tracking.em)

/** The logo if there is one, else the name's initials on the accent tint. */
@Composable
private fun LogoTile(image: ImageBitmap?, initials: String, size: Dp, dashed: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    val border = if (dashed) ZillitTheme.colors.borderStrong else ZillitTheme.colors.border
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(shape).background(Color.White).border(1.dp, border, shape),
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(crewPalette().accentTile).border(1.dp, border, shape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = initials,
                style = ZillitTheme.typography.titleMedium.copy(
                    fontSize = (size.value * INITIALS_SHARE).sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = crewPalette().grip,
            )
        }
    }
}

private const val INITIALS_SHARE = 0.34f
