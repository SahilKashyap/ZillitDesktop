package com.zillit.desktop.feature.dealmemo.domain

/**
 * A published rate for one role under one agreement.
 *
 * Keyed by the tuple (department × designation × branch × production type ×
 * budget envelope), which is why a lookup takes all of them and why
 * [RateResolution] exists to say which one answered.
 */
data class RateCardEntry(
    val id: String,
    val departmentIdentifier: String,
    val designationIdentifier: String,
    val branchIdentifier: String?,
    val unionIdentifier: String?,
    val productionType: String?,
    val currency: String?,
    /** Budget envelope this rate applies within. Null on either end is open. */
    val minBudget: Double?,
    val maxBudget: Double?,
    val hourly: RateTier?,
    val daily: RateTier?,
    val weekly: RateTier?,
) {
    /** Whether [budget] falls inside this entry's envelope. */
    fun coversBudget(budget: Double?): Boolean {
        if (budget == null) return true
        if (minBudget != null && budget < minBudget) return false
        if (maxBudget != null && budget > maxBudget) return false
        return true
    }
}

/**
 * One tier of a rate — hourly, daily or weekly.
 *
 * [workHours] is what makes a tier comparable: a rate card can publish both a
 * ten-hour and an eleven-hour day, and which one a deal binds to is decided by
 * the agreement rather than by whichever came back first.
 */
data class RateTier(
    val baseRate: Double?,
    val minRate: Double?,
    val maxRate: Double?,
    val workHours: Double?,
    /** `CWD`, `SWD` and so on, where the card distinguishes them. */
    val dayType: String? = null,
)

/**
 * The agreement's own scale, used where a role publishes no rate of its own.
 *
 * Most collective agreements author it this way: negotiated designations carry
 * only their hours, and the union-wide envelope is the applicable rate.
 */
data class BasicRateDetails(
    val hourly: RateTier? = null,
    val daily: RateTier? = null,
    val weekly: RateTier? = null,
    val dayType: String? = null,
)

/** Where a resolved figure came from, so the screen can say. */
enum class RateSource { Designation, Agreement, None }

/** One tier after the cascade, with its provenance. */
data class ResolvedTier(
    val baseRate: Double?,
    val minRate: Double?,
    val maxRate: Double?,
    val workHours: Double?,
    val baseSource: RateSource,
    val envelopeSource: RateSource,
) {
    val isEmpty: Boolean get() = baseRate == null && minRate == null && maxRate == null
}

/** The three tiers a deal binds to, resolved. */
data class RateResolution(
    val hourly: ResolvedTier?,
    val daily: ResolvedTier?,
    val weekly: ResolvedTier?,
    val dayType: String?,
) {
    val hasAnything: Boolean
        get() = listOfNotNull(hourly, daily, weekly).any { !it.isEmpty }
}

/**
 * The rate cascade: designation first, then the agreement's own scale.
 *
 * ## Why this is not just "prefer the first non-null"
 *
 * Two rules make it subtler, and both come from how agreements are actually
 * written:
 *
 *  - a rate card may publish **several entries per tier**, one per contracted
 *    day length, and the deal binds to the one whose hours match the
 *    agreement's — picking the first would put a crew member on a ten-hour
 *    scale under an eleven-hour agreement;
 *  - the fall-back is **per field**, not per tier. A designation that publishes
 *    only its hours still takes its rate from the agreement, and taking the
 *    whole tier from one side or the other loses one of the two.
 */
object RateCascade {

    /**
     * Resolves the tiers for one role.
     *
     * [entry] is the rate-card row that matched, or null when the role
     * publishes none — in which case the agreement's scale is the whole
     * answer, which is the common case for unnegotiated roles.
     */
    fun resolve(entry: RateCardEntry?, agreement: BasicRateDetails?): RateResolution {
        val hourly = merge(entry?.hourly, agreement?.hourly)
        val daily = merge(entry?.daily, agreement?.daily)
        val weekly = merge(entry?.weekly, agreement?.weekly)
        return RateResolution(
            hourly = hourly,
            daily = daily,
            weekly = weekly,
            dayType = entry?.daily?.dayType ?: agreement?.dayType,
        )
    }

    /**
     * Picks the entry a deal binds to from several published for one tier.
     *
     * Matched on hours against the agreement's own; with no agreement hours to
     * match, the first is taken — an arbitrary choice, but the alternative is
     * refusing to show a rate at all, and one published tier is usually the
     * whole story.
     */
    fun pickTier(published: List<RateTier>, agreementHours: Double?): RateTier? {
        if (published.isEmpty()) return null
        if (agreementHours == null) return published.first()
        return published.firstOrNull { it.workHours == agreementHours } ?: published.first()
    }

    /**
     * Narrows a rate card to the entries that apply to a selection.
     *
     * Budget is an envelope rather than a key: an entry with no bounds applies
     * to any budget, which is how most cards publish their default scale.
     */
    fun applicable(
        entries: List<RateCardEntry>,
        departmentIdentifier: String?,
        designationIdentifier: String?,
        productionType: String?,
        budget: Double?,
    ): List<RateCardEntry> = entries.filter { entry ->
        (departmentIdentifier == null || entry.departmentIdentifier == departmentIdentifier) &&
            (designationIdentifier == null || entry.designationIdentifier == designationIdentifier) &&
            (productionType == null || entry.productionType == null || entry.productionType == productionType) &&
            entry.coversBudget(budget)
    }

    /**
     * Whether [rate] sits inside what the agreement permits.
     *
     * A deal below the floor is the one this catches: paying under a
     * collective agreement is a dispute waiting to happen, and the person
     * writing the deal is usually the last to know the floor moved.
     */
    fun withinEnvelope(tier: ResolvedTier?, rate: Double): Boolean {
        val resolved = tier ?: return true
        if (resolved.minRate != null && rate < resolved.minRate) return false
        if (resolved.maxRate != null && rate > resolved.maxRate) return false
        return true
    }

    @Suppress("CyclomaticComplexMethod") // Per-field fallback: one branch per field and source.
    private fun merge(designation: RateTier?, agreement: RateTier?): ResolvedTier? {
        if (designation == null && agreement == null) return null
        return ResolvedTier(
            baseRate = designation?.baseRate ?: agreement?.baseRate,
            minRate = designation?.minRate ?: agreement?.minRate,
            maxRate = designation?.maxRate ?: agreement?.maxRate,
            workHours = designation?.workHours ?: agreement?.workHours,
            baseSource = when {
                designation?.baseRate != null -> RateSource.Designation
                agreement?.baseRate != null -> RateSource.Agreement
                else -> RateSource.None
            },
            envelopeSource = when {
                designation?.minRate != null || designation?.maxRate != null -> RateSource.Designation
                agreement?.minRate != null || agreement?.maxRate != null -> RateSource.Agreement
                else -> RateSource.None
            },
        )
    }
}
