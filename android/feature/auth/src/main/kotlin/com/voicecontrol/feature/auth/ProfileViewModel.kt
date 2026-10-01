package com.voicecontrol.feature.auth

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.data.profile.ProfileRepository
import com.voicecontrol.core.data.sync.SyncScheduler
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val sync: SyncScheduler,
) : MviViewModel<ProfileState, ProfileIntent, ProfileEffect>(ProfileState()) {

    init {
        viewModelScope.launch {
            val user = auth.user.first()
            setState { copy(draft = profiles.profile.first(), email = user?.email, signedIn = user != null, loaded = true) }
        }
    }

    override suspend fun handleIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.Edit -> setState { copy(draft = draft.with(intent.field, intent.value)) }
            ProfileIntent.Save -> {
                val error = currentState.draft.validationError()
                if (error != null) {
                    sendEffect(ProfileEffect.Message(error))
                    return
                }
                setState { copy(saving = true) }
                profiles.save(currentState.draft)
                if (currentState.signedIn) sync.syncNow()
                setState { copy(saving = false) }
                sendEffect(ProfileEffect.Message(if (currentState.signedIn) "Saved. Syncing to your account…" else "Saved on this phone"))
            }
            ProfileIntent.SignOut -> {
                auth.logout()
                profiles.clear()
                sendEffect(ProfileEffect.SignedOut)
            }
        }
    }
}
