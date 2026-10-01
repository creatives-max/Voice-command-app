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
    private val account: com.voicecontrol.core.data.account.AccountDataRepository,
) : MviViewModel<ProfileState, ProfileIntent, ProfileEffect>(ProfileState()) {

    init {
        viewModelScope.launch {
            val user = auth.user.first()
            val profile = profiles.profile.first()
            setState { copy(draft = profile, email = user?.email, signedIn = user != null, loaded = true) }
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
            is ProfileIntent.DeleteAccount -> {
                setState { copy(saving = true) }
                val result = account.deleteAccount(intent.password)
                setState { copy(saving = false) }
                result.fold(
                    onSuccess = {
                        sendEffect(ProfileEffect.Message("Your account and data were deleted"))
                        sendEffect(ProfileEffect.SignedOut)
                    },
                    onFailure = { sendEffect(ProfileEffect.Message(it.message ?: "Deletion failed")) },
                )
            }
            ProfileIntent.SignOut -> {
                auth.logout()
                profiles.clear()
                sendEffect(ProfileEffect.SignedOut)
            }
        }
    }
}
