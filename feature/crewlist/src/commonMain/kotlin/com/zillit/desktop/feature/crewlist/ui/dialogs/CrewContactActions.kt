package com.zillit.desktop.feature.crewlist.ui.dialogs

import com.zillit.desktop.feature.crewlist.domain.CrewMember

/** What pressing the profile drawer's actions does — the host's call, chat and mail. */
class CrewContactActions(
    val call: ((member: CrewMember, video: Boolean) -> Unit)? = null,
    val chat: ((member: CrewMember) -> Unit)? = null,
    val email: ((address: String) -> Unit)? = null,
)
