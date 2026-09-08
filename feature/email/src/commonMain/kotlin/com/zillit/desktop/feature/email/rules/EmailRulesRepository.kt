package com.zillit.desktop.feature.email.rules

import com.zillit.desktop.core.common.ZillitResult

interface EmailRulesRepository {
    suspend fun rules(): ZillitResult<List<EmailRule>>
    suspend fun create(rule: EmailRule): ZillitResult<EmailRule>
    suspend fun update(rule: EmailRule): ZillitResult<EmailRule>
    suspend fun delete(ruleId: String): ZillitResult<Unit>
    /** `POST email-rules/reorder { rule_ids }` — the new priority order, first runs first. */
    suspend fun reorder(ruleIds: List<String>): ZillitResult<Unit>
    suspend fun executions(ruleId: String, limit: Int = EXECUTIONS_PAGE, skip: Int = 0): ZillitResult<RuleExecutionPage>

    companion object {
        const val EXECUTIONS_PAGE = 20
    }
}

/** Where a rule may save attachments: the Drive's folders, browsed one level at a time. */
fun interface DriveFolderSource {
    suspend fun children(parentId: String?): ZillitResult<List<DriveFolderOption>>
}
