package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsRepository
import com.zillit.desktop.feature.selectstills.domain.UploadDeclaration
import com.zillit.desktop.feature.selectstills.domain.stillsTypeOf

/** At most five angles per person. */
const val MAX_HEADSHOTS: Int = 5

val HEADSHOT_TYPES: List<String> = listOf("image/jpeg", "image/png", "image/webp")

@Suppress("MagicNumber")
val MAX_HEADSHOT_BYTES: Long = 25L * 1024 * 1024

/** The two reasons a person can overrule: it IS them, add it anyway. */
val FORCEABLE_HEADSHOT_REASONS: Set<String> = setOf(
    "still_kills_headshot_matches_other",
    "still_kills_headshot_unlike_others",
)

/** What the service made of one headshot, with the file kept where "add anyway" can use it. */
data class HeadshotOutcome(
    val name: String,
    val path: String,
    val ok: Boolean,
    val reason: String = "",
    /** Who it looked like, when that is why it was turned away. */
    val matchedName: String = "",
) {
    val canForce: Boolean get() = !ok && path.isNotBlank() && reason in FORCEABLE_HEADSHOT_REASONS
}

/**
 * The result of adding headshots.
 *
 * [failure] is set only when a *call* failed outright — the toast then shows
 * the service's own words. A headshot the service merely turned away is in
 * [results], which is not a failure of the request.
 */
data class HeadshotsAdded(
    val member: Member? = null,
    val results: List<HeadshotOutcome> = emptyList(),
    val failure: ZillitError? = null,
) {
    val anyRefused: Boolean get() = results.any { !it.ok }
}

/**
 * Adding headshots to a cast member: ask for links, send each one straight to
 * storage, then have the service check them (exactly one face; not somebody
 * already on the list; like this member's other headshots).
 *
 * Each file is answered on its own. A headshot the service turns away is
 * deleted there, so "add anyway" sends the same file again with `force`.
 */
class HeadshotFlow(
    private val repository: StillsRepository,
    private val uploader: StillsUploader,
    private val files: StillsFileReader,
) {

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    // Three calls in sequence, each able to end the flow: screen the files, ask
    // for links, send them, have the service check them. An early return is how
    // each stage says "nothing got past me".
    suspend fun add(memberId: String, picks: List<StillsPick>, force: Boolean = false): HeadshotsAdded {
        val results = mutableListOf<HeadshotOutcome>()
        val usable = mutableListOf<Pair<StillsPick, String>>()
        picks.forEach { pick ->
            val type = stillsTypeOf(pick.name, pick.type)
            when {
                type !in HEADSHOT_TYPES ->
                    results += HeadshotOutcome(pick.name, "", false, "still_kills_file_type_unsupported")
                pick.size <= 0 || pick.size > MAX_HEADSHOT_BYTES ->
                    results += HeadshotOutcome(pick.name, "", false, "still_kills_file_too_large")
                else -> usable += pick to type
            }
        }
        if (usable.isEmpty()) return HeadshotsAdded(results = results)

        val signed = repository.presignHeadshots(
            memberId,
            usable.map { (pick, type) ->
                UploadDeclaration(uniqueId = "", name = pick.name, type = type, size = pick.size)
            },
        )
        if (signed is ZillitResult.Failure) return HeadshotsAdded(results = results, failure = signed.error)
        val links = (signed as ZillitResult.Success).data

        val sent = mutableListOf<Pair<String, StillsPick>>()
        links.forEachIndexed { index, link ->
            val (pick, type) = usable.getOrNull(index) ?: return@forEachIndexed
            if (link.url.isBlank()) {
                results += HeadshotOutcome(pick.name, "", false, link.refused.ifBlank { "still_kills_file_missing" })
                return@forEachIndexed
            }
            val bytes = files.readAll(pick.path)
            val outcome = if (bytes == null) {
                PutOutcome.Failed(0)
            } else {
                uploader.putBytes(link.url, link.headers, bytes, type)
            }
            if (outcome is PutOutcome.Ok) {
                sent += link.headshotId to pick
            } else {
                results += HeadshotOutcome(pick.name, pick.path, false, "still_kills_file_missing")
            }
        }
        if (sent.isEmpty()) return HeadshotsAdded(results = results)

        val checked = repository.completeHeadshots(memberId, sent.map { it.first }, force)
        if (checked is ZillitResult.Failure) return HeadshotsAdded(results = results, failure = checked.error)
        val answer = (checked as ZillitResult.Success).data.value

        val byId = sent.toMap()
        answer.results.forEach { verdict ->
            val pick = byId[verdict.headshotId]
            results += HeadshotOutcome(
                name = pick?.name.orEmpty(),
                // Kept only where "add anyway" can use it.
                path = if (!verdict.ok && verdict.reason in FORCEABLE_HEADSHOT_REASONS) pick?.path.orEmpty() else "",
                ok = verdict.ok,
                reason = verdict.reason,
                matchedName = verdict.matchedName,
            )
        }
        return HeadshotsAdded(member = answer.member, results = results)
    }
}
