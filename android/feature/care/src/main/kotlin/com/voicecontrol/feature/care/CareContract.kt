package com.voicecontrol.feature.care

import com.voicecontrol.core.data.care.CareUnavailable
import com.voicecontrol.core.network.dto.CareEventDto
import com.voicecontrol.core.network.dto.CareInviteDto
import com.voicecontrol.core.network.dto.CareLinkDto
import java.time.Instant

data class CareState(
    val loading: Boolean = true,
    val unavailable: CareUnavailable? = null,
    /** People who help this user. */
    val helpers: List<CareLinkDto> = emptyList(),
    /** Open invites of this user. */
    val pending: List<CareLinkDto> = emptyList(),
    /** People this user helps (managed on the dashboard). */
    val helping: List<CareLinkDto> = emptyList(),
    /** Permissions for the next invite. */
    val chosen: Set<String> = setOf(CareText.EDIT_FLOWS),
    /** The invite just created, with its code. */
    val invite: CareInviteDto? = null,
    /** Activity of the helpers whose log is open. */
    val events: Map<String, List<CareEventDto>> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val myEmail: String? = null,
)

sealed interface CareIntent {
    data object Refresh : CareIntent
    data class ChoosePermission(val permission: String, val on: Boolean) : CareIntent
    data object CreateInvite : CareIntent
    data object ShareInvite : CareIntent
    data class Cancel(val linkId: String) : CareIntent
    data class SetPermission(val linkId: String, val permission: String, val on: Boolean) : CareIntent
    data class End(val linkId: String) : CareIntent
    data class ToggleActivity(val linkId: String) : CareIntent
}

sealed interface CareEffect {
    data class Message(val text: String) : CareEffect
    data class Share(val text: String) : CareEffect
}

/** Words shown for permissions and activity (same meaning as the dashboard's). */
object CareText {
    const val EDIT_FLOWS = "edit_flows"
    const val RUN_FLOWS = "run_flows"
    const val VIEW_HISTORY = "view_history"
    val permissions = listOf(EDIT_FLOWS, RUN_FLOWS, VIEW_HISTORY)

    fun permission(id: String): String = when (id) {
        EDIT_FLOWS -> "Edit my flows and when they run"
        RUN_FLOWS -> "Run flows on this phone"
        VIEW_HISTORY -> "See my run history"
        else -> id.replace('_', ' ')
    }

    fun name(link: CareLinkDto): String = link.otherName?.takeIf { it.isNotBlank() } ?: link.otherEmail ?: "Someone"

    /** The code read letter by letter, for TalkBack ("K 7 Q M, 2 9 P X"). */
    fun spoken(code: String): String = code.split('-').joinToString(", ") { it.toList().joinToString(" ") }

    fun minutesLeft(expiresAt: String?, now: Instant = Instant.now()): Long {
        val end = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return 0
        val seconds = end.epochSecond - now.epochSecond
        return if (seconds <= 0) 0 else (seconds + 59) / 60
    }

    fun describe(e: CareEventDto, myEmail: String?): String {
        val who = if (e.actorEmail != null && e.actorEmail == myEmail) "You" else e.actorEmail ?: "Someone"
        val flow = e.details["flowName"]?.let { " “$it”" } ?: " a flow"
        val perms = e.details["permissions"].orEmpty().split(',').filter { it.isNotBlank() }
            .joinToString(", ") { permission(it).lowercase() }.ifEmpty { "only seeing flows" }
        return when (e.action) {
            "invited" -> "$who created an invite ($perms)"
            "accepted" -> "$who accepted the invite"
            "permissions_changed" -> "$who changed permissions to $perms"
            "flow_edited" -> "$who edited$flow"
            "flow_restored" -> "$who restored an older version of$flow"
            "trigger_added" -> "$who added a trigger to$flow"
            "trigger_changed" -> "$who changed a trigger"
            "trigger_removed" -> "$who removed a trigger"
            "flow_added" -> "$who added a flow from the marketplace"
            "flow_run" -> "$who ran a flow on your phone"
            "run_stopped" -> "$who stopped a run"
            "invite_cancelled" -> "$who cancelled the invite"
            "ended" -> "$who ended the link"
            else -> "$who: ${e.action.replace('_', ' ')}"
        }
    }

    fun split(links: List<CareLinkDto>): Triple<List<CareLinkDto>, List<CareLinkDto>, List<CareLinkDto>> = Triple(
        links.filter { it.role == "receiver" && it.status == "ACTIVE" },
        links.filter { it.role == "receiver" && it.status == "PENDING" },
        links.filter { it.role == "caregiver" && it.status == "ACTIVE" },
    )
}
