package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The "Sent by" menu's sender list from `GET distributions/senders`, asked
 * once per production. Not shipped everywhere: a failure or an empty answer
 * leaves the menu to the senders the rows name.
 */
internal class HistorySenderSource(
    private val repository: DocDistRepository,
    private val launch: (suspend CoroutineScope.() -> Unit) -> Job,
) {
    private var asked = false

    fun askOnce(onLoaded: (List<DistributionSender>) -> Unit) {
        if (asked) return
        asked = true
        launch {
            val fetched = (repository.senders() as? ZillitResult.Success)?.data.orEmpty()
            if (fetched.isNotEmpty()) onLoaded(fetched)
        }
    }

    /** A production switch starts the question over. */
    fun reset() {
        asked = false
    }
}
