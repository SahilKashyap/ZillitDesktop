package com.zillit.desktop.feature.email.rules

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * An inbox rule — the web's `email_v2/emailRules` and Android's
 * `EmailRule`, on the same wire (`/v2/email-rules`): when a message matching
 * the conditions arrives, the actions run in order, and `stopOnMatch` ends
 * the run at this rule. Rules run in [priority] order, lowest first.
 */
data class EmailRule(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val stopOnMatch: Boolean = false,
    val matchType: RuleMatchType = RuleMatchType.All,
    val conditions: List<RuleCondition> = listOf(RuleCondition()),
    val actions: List<RuleAction> = listOf(RuleAction.SaveAttachmentsToDrive()),
    val createdMillis: Long = 0,
    val updatedMillis: Long = 0,
) {
    val isNew: Boolean get() = id.isBlank()

    /** Why the rule cannot be saved, or null. Mirrors Android's `RuleValidationIssue`. */
    val validationIssue: String?
        get() = when {
            name.isBlank() -> str(S.desktop_email_rule_name_required)
            name.length > NAME_MAX_LENGTH -> str(S.desktop_email_rule_name_too_long)
            conditions.isEmpty() -> str(S.desktop_email_rule_condition_required)
            conditions.any { !it.isValid } -> str(S.desktop_email_rule_condition_value_required)
            actions.isEmpty() -> str(S.desktop_email_rule_action_required)
            actions.any { !it.isValid } -> actions.first { !it.isValid }.invalidReason
            else -> null
        }

    val isValid: Boolean get() = validationIssue == null

    /** One line that says what the rule does — Android's `RuleSummary`. */
    fun summary(): String {
        val joiner = " ${str(if (matchType == RuleMatchType.All) S.email_rule_joiner_and else S.email_rule_joiner_or)} "
        val whenPart = conditions.joinToString(joiner) { it.describe() }
        val thenPart = actions.joinToString(", ") { it.describe() }
        return "$whenPart → $thenPart"
    }

    companion object {
        const val NAME_MAX_LENGTH = 80
        const val MAX_RULES = 50
    }
}

enum class RuleMatchType(val wire: String, private val labelKey: String) {
    All("all", S.desktop_email_rule_all_conditions),
    Any("any", S.desktop_email_rule_any_condition),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromWire(raw: String?): RuleMatchType =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: All
    }
}

enum class ConditionOperator(val wire: String, private val labelKey: String) {
    Is("is", S.email_rule_op_is),
    IsNot("is_not", S.email_rule_op_is_not),
    Contains("contains", S.email_rule_op_contains),
    NotContains("not_contains", S.email_rule_op_not_contains),
    StartsWith("starts_with", S.email_rule_op_starts_with),
    EndsWith("ends_with", S.email_rule_op_ends_with),
    IsTrue("is_true", S.desktop_email_rule_op_is_true),
    IsFalse("is_false", S.desktop_email_rule_op_is_false),
    ;

    val label: String get() = str(labelKey)

    companion object {
        val TEXT: List<ConditionOperator> = listOf(Is, IsNot, Contains, NotContains, StartsWith, EndsWith)
        fun fromWire(raw: String?): ConditionOperator? = entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
    }
}

/** What a condition looks at; the operators it takes; whether it needs a value. The web's `CONDITION_FIELDS`. */
enum class ConditionField(
    val wire: String,
    private val labelKey: String,
    val operators: List<ConditionOperator>,
    val needsValue: Boolean,
) {
    Always("always", S.email_rule_field_always, listOf(ConditionOperator.IsTrue), needsValue = false),
    From("from", S.email_rule_field_from, ConditionOperator.TEXT, needsValue = true),
    FromDomain("from_domain", S.desktop_email_rule_field_from_domain, ConditionOperator.TEXT, needsValue = true),
    Subject("subject", S.email_rule_field_subject, ConditionOperator.TEXT, needsValue = true),
    Body("body", S.email_rule_field_body, ConditionOperator.TEXT, needsValue = true),
    SubjectOrBody("subject_or_body", S.email_rule_field_subject_or_body, ConditionOperator.TEXT, needsValue = true),
    HasAttachment(
        "has_attachment",
        S.email_rule_field_has_attachment,
        listOf(ConditionOperator.IsTrue, ConditionOperator.IsFalse),
        needsValue = false,
    ),
    ;

    val label: String get() = str(labelKey)

    val defaultOperator: ConditionOperator get() = operators.first()

    companion object {
        fun fromWire(raw: String?): ConditionField? = entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
    }
}

data class RuleCondition(
    val field: ConditionField = ConditionField.From,
    val operator: ConditionOperator = ConditionOperator.Is,
    val value: String = "",
) {
    val isValid: Boolean get() = !this.field.needsValue || value.isNotBlank()

    /** The operator kept, or the field's first when it does not apply — the web's `operatorForField`. */
    fun withField(next: ConditionField): RuleCondition =
        copy(
            field = next,
            operator = if (operator in next.operators) operator else next.defaultOperator,
            value = if (next.needsValue) value else "",
        )

    fun describe(): String = when {
        field == ConditionField.Always -> str(S.desktop_email_rule_summary_any_email)
        field.needsValue -> "${field.label.lowercase()} ${operator.label.lowercase()} \"$value\""
        else -> "${field.label.lowercase()} ${operator.label.lowercase()}"
    }
}

enum class RuleActionType(val wire: String, private val labelKey: String) {
    SaveAttachmentsToDrive("save_attachments_to_drive", S.email_rule_action_save_to_drive),
    MoveToFolder("move_to_folder", S.move_to_folder),
    ForwardTo("forward_to", S.desktop_email_forward_to),
    MarkRead("mark_read", S.email_rule_action_mark_read),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromWire(raw: String?): RuleActionType? = entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
    }
}

/** What a rule does with a matching message. The web's `ACTION_TYPES` with their payloads. */
sealed interface RuleAction {
    val type: RuleActionType
    val isValid: Boolean
    val invalidReason: String?
    fun describe(): String

    data class SaveAttachmentsToDrive(
        val driveFolderId: String = "",
        val driveFolderName: String = "",
        /** Lower-case, no leading dot; empty means every attachment. */
        val extensions: List<String> = emptyList(),
        /** 0 means no limit. */
        val maxSizeBytes: Long = 0,
    ) : RuleAction {
        override val type = RuleActionType.SaveAttachmentsToDrive
        override val invalidReason: String?
            get() = when {
                driveFolderId.isBlank() -> str(S.desktop_email_rule_pick_drive_folder)
                extensions.size > MAX_EXTENSIONS -> str(S.desktop_email_rule_too_many_file_types, MAX_EXTENSIONS)
                maxSizeBytes !in 0..MAX_ATTACHMENT_SIZE_BYTES -> str(S.desktop_email_rule_size_out_of_range)
                else -> null
            }
        override val isValid: Boolean get() = invalidReason == null
        override fun describe(): String = str(S.email_rule_action_save_to_drive) +
            driveFolderName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

        companion object {
            const val MAX_EXTENSIONS = 20
            const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024 * 1024

            /** "pdf, .PNG ,docx" → ["pdf", "png", "docx"] — what the web sends. */
            fun parseExtensions(text: String): List<String> =
                text.split(',')
                    .map { it.trim().removePrefix(".").lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
        }
    }

    data class MoveToFolder(val folderName: String = "") : RuleAction {
        override val type = RuleActionType.MoveToFolder
        override val invalidReason: String?
            get() = when {
                folderName.isBlank() -> str(S.desktop_email_rule_pick_folder)
                folderName.uppercase() in MOVE_EXCLUDED_FOLDERS ->
                    str(S.desktop_email_rule_cannot_move_into, folderName.uppercase())
                else -> null
            }
        override val isValid: Boolean get() = invalidReason == null
        override fun describe(): String = str(S.email_rule_summary_move_to, folderName)

        companion object {
            /** The web's `MOVE_EXCLUDED_FOLDERS`: system folders a rule may not move into. */
            val MOVE_EXCLUDED_FOLDERS = setOf("INBOX", "DRAFTS", "SENT")
        }
    }

    data class ForwardTo(val email: String = "") : RuleAction {
        override val type = RuleActionType.ForwardTo
        override val invalidReason: String?
            get() = if (EMAIL.matches(email.trim())) null else str(S.desktop_email_rule_forward_address_required)
        override val isValid: Boolean get() = invalidReason == null
        override fun describe(): String = str(S.email_rule_summary_forward_to, email.trim())

        companion object {
            private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        }
    }

    data object MarkRead : RuleAction {
        override val type = RuleActionType.MarkRead
        override val invalidReason: String? = null
        override val isValid: Boolean = true
        override fun describe(): String = str(S.email_rule_action_mark_read)
    }

    companion object {
        fun blank(type: RuleActionType): RuleAction = when (type) {
            RuleActionType.SaveAttachmentsToDrive -> SaveAttachmentsToDrive()
            RuleActionType.MoveToFolder -> MoveToFolder()
            RuleActionType.ForwardTo -> ForwardTo()
            RuleActionType.MarkRead -> MarkRead
        }
    }
}

/** Which folders a Move action may target: the mailbox's folders minus the system ones. */
fun selectableMoveFolders(folderNames: List<String>): List<String> =
    folderNames
        .filter { it.isNotBlank() && it.uppercase() !in RuleAction.MoveToFolder.MOVE_EXCLUDED_FOLDERS }
        .distinct()

enum class ExecutionStatus(val wire: String, private val labelKey: String) {
    Pending("pending", S.email_rule_exec_pending),
    Success("success", S.done_text),
    Partial("partial", S.email_rule_exec_partial),
    Failed("failed", S.email_rule_exec_failed),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromWire(raw: String?): ExecutionStatus =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: Pending
    }
}

/** One run of a rule against one message — the History drawer's row. */
data class RuleExecution(
    val id: String,
    val ruleId: String,
    val mailboxEmail: String = "",
    val messageId: String = "",
    val status: ExecutionStatus = ExecutionStatus.Pending,
    val attempts: Int = 0,
    val results: List<ExecutionActionResult> = emptyList(),
    val error: String? = null,
    val createdMillis: Long = 0,
)

data class ExecutionActionResult(val type: RuleActionType?, val status: ExecutionStatus, val detail: String? = null)

data class RuleExecutionPage(val executions: List<RuleExecution>, val total: Int)

/** A Drive folder as the "Save attachments" picker lists it. */
data class DriveFolderOption(val id: String, val name: String)
