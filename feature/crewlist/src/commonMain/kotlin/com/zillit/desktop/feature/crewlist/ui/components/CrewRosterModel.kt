package com.zillit.desktop.feature.crewlist.ui.components

import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.PhoneProblem

/** What the roster draws from — the pruned units and everything a row needs. */
internal class CrewRosterModel(
    val units: List<CrewUnit>,
    val loading: Boolean,
    /** An `other` production: a Staff List, no unit bands. */
    val hideUnitBands: Boolean,
    val editable: Boolean,
    val overrides: Map<String, MemberOverride>,
    val problems: Map<String, PhoneProblem>,
    val dialCodes: List<DialCode>,
)
