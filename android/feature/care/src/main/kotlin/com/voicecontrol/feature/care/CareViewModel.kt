package com.voicecontrol.feature.care

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.care.CareRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CareViewModel @Inject constructor(private val care: CareRepository) : MviViewModel<CareState, CareIntent, CareEffect>(CareState()) {

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val unavailable = care.unavailable()
        if (unavailable != null) {
            setState { copy(loading = false, unavailable = unavailable) }
            return
        }
        care.links().fold(
            onSuccess = { links ->
                val (helpers, pending, helping) = CareText.split(links)
                val me = care.myEmail()
                setState { copy(loading = false, unavailable = null, helpers = helpers, pending = pending, helping = helping, error = null, myEmail = me) }
            },
            onFailure = { setState { copy(loading = false, error = "Couldn't load your caregivers. Check the internet connection.") } },
        )
    }

    override suspend fun handleIntent(intent: CareIntent) {
        when (intent) {
            CareIntent.Refresh -> refresh()
            is CareIntent.ChoosePermission -> setState { copy(chosen = if (intent.on) chosen + intent.permission else chosen - intent.permission) }
            CareIntent.CreateInvite -> {
                setState { copy(busy = true) }
                care.invite(currentState.chosen).fold(
                    onSuccess = { invite ->
                        setState { copy(busy = false, invite = invite) }
                        refresh()
                    },
                    onFailure = {
                        setState { copy(busy = false) }
                        sendEffect(CareEffect.Message(it.message ?: "Couldn't create an invite"))
                    },
                )
            }
            CareIntent.ShareInvite -> currentState.invite?.let { invite ->
                sendEffect(
                    CareEffect.Share(
                        "My VoiceControl helper code is ${invite.code}. Enter it on the VoiceControl dashboard under Caregiving within " +
                            "${CareText.minutesLeft(invite.expiresAt)} minutes.",
                    ),
                )
            }
            is CareIntent.Cancel -> care.end(intent.linkId).fold(
                onSuccess = {
                    setState { copy(invite = invite?.takeIf { it.link.id != intent.linkId }) }
                    refresh()
                },
                onFailure = { sendEffect(CareEffect.Message(it.message ?: "Couldn't cancel the invite")) },
            )
            is CareIntent.SetPermission -> {
                val link = currentState.helpers.firstOrNull { it.id == intent.linkId } ?: return
                val next = if (intent.on) link.permissions.toSet() + intent.permission else link.permissions.toSet() - intent.permission
                care.setPermissions(link.id, next).fold(
                    onSuccess = { updated -> setState { copy(helpers = helpers.map { if (it.id == updated.id) updated else it }) } },
                    onFailure = { sendEffect(CareEffect.Message(it.message ?: "Couldn't change permissions")) },
                )
            }
            is CareIntent.End -> {
                val link = currentState.helpers.firstOrNull { it.id == intent.linkId } ?: currentState.helping.firstOrNull { it.id == intent.linkId }
                care.end(intent.linkId).fold(
                    onSuccess = {
                        sendEffect(CareEffect.Message(link?.let { "${CareText.name(it)} can no longer help you" } ?: "Ended"))
                        refresh()
                    },
                    onFailure = { sendEffect(CareEffect.Message(it.message ?: "Couldn't end it")) },
                )
            }
            is CareIntent.ToggleActivity -> {
                if (intent.linkId in currentState.events) {
                    setState { copy(events = events - intent.linkId) }
                    return
                }
                care.events(intent.linkId).fold(
                    onSuccess = { list -> setState { copy(events = events + (intent.linkId to list)) } },
                    onFailure = { sendEffect(CareEffect.Message("Couldn't load the activity")) },
                )
            }
        }
    }
}
