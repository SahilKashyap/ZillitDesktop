package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.CrewMember
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.digitsOnly
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.urlEncode
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private const val MAX_TITLE = 160
private const val MAX_BODY = 4000
private const val BODY_WARN = 400
private const val DIALOG_WIDTH = 900
private val LIST_MAX = 260.dp
private val QUICK_FIELD = 472.dp
private val QUICK_HALF = 220.dp
private const val GROUP_CREW = "crew"
private const val GROUP_VENDORS = "vendors"
private const val GROUP_CONTACTS = "contacts"
private val GROUPS = listOf(GROUP_CREW, GROUP_VENDORS, GROUP_CONTACTS)

/** One line of the recipient list: who, and how to reach them. */
private data class Recipient(val id: String, val name: String, val sub: String)

/** What the service answered about a sent request. */
private data class Sent(val notified: Int, val skippedSelf: Boolean, val offApp: List<Rec>)

/**
 * "Send a request" — ask someone to act on a record (the web's `SendRequestModal`, `POST /requests`).
 *
 * Shared by fittings, cleaning, the three ticket boards and vendors: callers pass the [entityType]
 * (`FITTING`, `CLEANING`, `ALTERATION`…), the record's [entityId] (null for a whole-board chase) and a
 * starting subject and message. Crew are told inside the app; vendors and outside contacts never sign
 * in, so for them the written message comes back to be passed on (WhatsApp, email, copy).
 *
 * Crew comes from Zillit's own department list through the host's `crew()`, never from the service's
 * `/members` (its access list). Opening is what seeds the form, so one dialog serves every record on a
 * page and a refetch behind it never rewrites what someone is part-way through typing.
 */
@Composable
fun SendRequestDialog(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    entityType: String,
    entityId: String?,
    defaultTitle: String,
    defaultBody: String,
) {
    if (!open) return
    val ctx = LocalSync.current
    var subject by remember { mutableStateOf(defaultTitle.take(MAX_TITLE)) }
    var message by remember { mutableStateOf(defaultBody.take(MAX_BODY)) }
    var group by remember { mutableStateOf(GROUP_CREW) }
    var q by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<Sent?>(null) }
    var adding by remember { mutableStateOf("") }
    val picked = remember { mapOf(GROUP_CREW to mutableStateListOf<String>(), GROUP_VENDORS to mutableStateListOf(), GROUP_CONTACTS to mutableStateListOf()) }
    var crew by remember { mutableStateOf<List<CrewMember>?>(null) }
    var vendors by remember { mutableStateOf<List<Rec>?>(null) }
    var contacts by remember { mutableStateOf<List<Rec>?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { crew = ctx.host.crew() }
    LaunchedEffect(reload) {
        vendors = (ctx.api.get("/vendors") as? ZillitResult.Success)?.data?.rows.orEmpty()
        contacts = (ctx.api.get("/contacts") as? ZillitResult.Success)?.data?.rows.orEmpty()
    }

    val toggle = { g: String, id: String ->
        val list = picked.getValue(g)
        if (id in list) list.remove(id) else list.add(id)
    }
    val rows = recipients(group, q, crew.orEmpty(), vendors.orEmpty(), contacts.orEmpty())
    val count = picked.values.sumOf { it.size }
    val canSend = count > 0 && subject.isNotBlank() && message.isNotBlank()
    val text = "${subject.trim()}\n\n${message.trim()}"

    val send: () -> Unit = {
        sending = true
        ctx.scope.launch {
            val answer = ctx.write {
                ctx.api.post(
                    "/requests",
                    body(
                        "title" to subject.trim(),
                        "body" to message.trim(),
                        "entity_type" to entityType,
                        "entity_id" to entityId,
                        "crew_user_ids" to picked.getValue(GROUP_CREW).toList(),
                        "vendor_ids" to picked.getValue(GROUP_VENDORS).toList(),
                        "contact_ids" to picked.getValue(GROUP_CONTACTS).toList(),
                    ),
                )
            }
            sending = false
            val r = answer?.rec ?: return@launch
            val offApp = r.recs("off_app")
            // Close only on a clean send to people who were reached; anything else has something left to say.
            if (offApp.isEmpty() && r.int("notified") > 0) onClose() else sent = Sent(r.int("notified"), r.bool("skipped_self"), offApp)
        }
    }

    SyncDialogShell(
        title = title,
        visible = true,
        onDismiss = onClose,
        width = DIALOG_WIDTH.dp,
        actions = {
            if (sent != null) {
                ZillitButton(t("csync_done"), onClick = onClose)
            } else {
                ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary)
                ZillitButton(
                    fill(t("csync_send_to_n"), "n" to if (count > 0) count else "…"),
                    onClick = send,
                    leadingIcon = ZillitIcons.Send,
                    enabled = canSend && !sending,
                    loading = sending,
                )
            }
        },
    ) {
        val result = sent
        if (result != null) {
            SentView(result, text, subject, message)
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                TextInput(subject, { subject = it.take(MAX_TITLE) }, t("csync_request_subject"), Modifier.fillMaxWidth())
                TextInput(
                    message,
                    { message = it.take(MAX_BODY) },
                    t("csync_request_message"),
                    Modifier.fillMaxWidth(),
                    multiline = true,
                    help = if (message.length > MAX_BODY - BODY_WARN) t("csync_chars_left", "n" to MAX_BODY - message.length) else null,
                )
                ZillitText(
                    t("csync_send_to") + if (count > 0) " ($count)" else "",
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitTabStrip(
                    tabs = GROUPS.map { g ->
                        ZillitTab(g, t("csync_request_group_$g") + picked.getValue(g).size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty())
                    },
                    activeId = group,
                    onSelect = { group = it; q = "" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    ZillitSearchField(q, { q = it }, Modifier.weight(1f), placeholder = t("csync_request_search_$group"))
                    if (group == GROUP_CREW) MutedText(t("csync_request_everyone"))
                    if (ctx.canPost && group == GROUP_VENDORS) {
                        ZillitButton(t("csync_new_vendor"), onClick = { adding = GROUP_VENDORS }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
                    }
                    if (ctx.canPost && group == GROUP_CONTACTS) {
                        ZillitButton(t("csync_new_contact"), onClick = { adding = GROUP_CONTACTS }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
                    }
                }
                RecipientList(rows, picked.getValue(group)) { toggle(group, it) }
                MutedText(t("csync_request_how"), maxLines = 3)
            }
        }
    }

    QuickAddContact(adding == GROUP_CONTACTS, { adding = "" }) { id ->
        reload++
        toggle(GROUP_CONTACTS, id)
    }
    NewVendorDialog(adding == GROUP_VENDORS, { adding = "" }) { vendor ->
        reload++
        toggle(GROUP_VENDORS, vendor.id)
    }
}

private fun recipients(group: String, q: String, crew: List<CrewMember>, vendors: List<Rec>, contacts: List<Rec>): List<Recipient> {
    val needle = q.trim().lowercase()
    fun match(vararg parts: String) = needle.isEmpty() || parts.any { it.lowercase().contains(needle) }
    return when (group) {
        GROUP_CREW -> crew.filter { match(it.name, it.email, it.department) }
            .map { Recipient(it.id, it.name, listOf(it.department, it.email).filter(String::isNotBlank).joinToString(" · ")) }
        GROUP_VENDORS -> vendors.filter { match(it.str("name"), it.str("contact_name"), it.str("email"), it.str("phone")) }
            .map { Recipient(it.id, it.str("name"), listOf(it.str("contact_name"), it.str("phone"), it.str("email")).filter(String::isNotBlank).joinToString(" · ")) }
        else -> contacts.filter { match(it.str("name"), it.str("company"), it.str("role"), it.str("email"), it.str("phone")) }
            .map { Recipient(it.id, it.str("name"), listOf(it.str("role"), it.str("company"), it.str("phone"), it.str("email")).filter(String::isNotBlank).joinToString(" · ")) }
    }
}

/** The bordered box of recipient rows: a tick, then the name over what they do and how to reach them. */
@Composable
private fun RecipientList(rows: List<Recipient>, picked: List<String>, onToggle: (String) -> Unit) {
    if (rows.isEmpty()) {
        MutedText(t("csync_nobody_here_yet"))
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = LIST_MAX).verticalScroll(rememberScrollState())) {
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(r.id) }.padding(vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitCheckbox(r.id in picked, { onToggle(r.id) })
                Column(Modifier.weight(1f)) {
                    RowTitle(r.name)
                    if (r.sub.isNotBlank()) MutedText(r.sub)
                }
            }
        }
    }
}

/** After a send: how many were told, and for anyone off the app the message to pass on (WhatsApp, email, copy). */
@Composable
private fun SentView(sent: Sent, text: String, subject: String, message: String) {
    val ctx = LocalSync.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val line = when {
            sent.notified > 0 -> fill(t(if (sent.notified == 1) "csync_request_notified_one" else "csync_request_notified"), "n" to sent.notified)
            sent.skippedSelf -> t("csync_request_only_you")
            sent.offApp.isNotEmpty() -> t("csync_request_no_crew")
            else -> t("csync_request_nothing_sent")
        }
        WfNotice(line)
        if (sent.offApp.isNotEmpty()) {
            MutedText(t("csync_request_pass_on"), maxLines = 3)
            sent.offApp.forEach { r ->
                val phone = r.str("phone")
                val email = r.str("email")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(r.str("name"))
                        MutedText(listOf(phone, email).filter(String::isNotBlank).joinToString(" · ").ifBlank { t("csync_no_contact_details") })
                    }
                    if (phone.isNotBlank()) {
                        ZillitButton(
                            "WhatsApp",
                            onClick = { ctx.host.openUrl("https://wa.me/${digitsOnly(phone)}?text=${urlEncode(text)}") },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                    if (email.isNotBlank()) {
                        ZillitButton(
                            t("csync_email"),
                            onClick = { ctx.host.openUrl("mailto:$email?subject=${urlEncode(subject)}&body=${urlEncode(message)}") },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                    ZillitButton(
                        t("csync_copy"),
                        onClick = {
                            copyTextToClipboard(text)
                            ctx.toast(t("csync_copied"), true)
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Copy,
                    )
                }
            }
        }
    }
}

/** A small "name someone new" dialog for an outside contact (`POST /contacts`); [onCreated] gets its id. */
@Composable
private fun QuickAddContact(open: Boolean, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var company by remember(open) { mutableStateOf("") }
    var role by remember(open) { mutableStateOf("") }
    var email by remember(open) { mutableStateOf("") }
    var phone by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_contact"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post(
                        "/contacts",
                        body("name" to name.trim(), "company" to company.trim(), "role" to role.trim(), "email" to email.trim(), "phone" to phone.trim()),
                    )
                }
                saving = false
                if (answer != null) {
                    answer.rec?.takeIf { it.id.isNotEmpty() }?.let { onCreated(it.id) }
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = 520.dp,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), Modifier.width(QUICK_FIELD))
            TextInput(company, { company = it }, t("csync_field_company"), Modifier.width(QUICK_HALF))
            TextInput(role, { role = it }, t("csync_field_what_they_do"), Modifier.width(QUICK_HALF))
            TextInput(email, { email = it }, t("csync_field_email"), Modifier.width(QUICK_HALF))
            TextInput(phone, { phone = it }, t("csync_field_phone"), Modifier.width(QUICK_HALF))
        }
    }
}
