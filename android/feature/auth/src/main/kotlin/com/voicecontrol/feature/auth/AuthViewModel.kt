package com.voicecontrol.feature.auth

import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : MviViewModel<AuthState, AuthIntent, AuthEffect>(AuthState()) {

    override suspend fun handleIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.EmailChanged -> setState { copy(email = intent.value, error = null) }
            is AuthIntent.PasswordChanged -> setState { copy(password = intent.value, error = null) }
            is AuthIntent.NameChanged -> setState { copy(name = intent.value) }
            AuthIntent.ToggleMode -> setState { copy(registerMode = !registerMode, error = null) }
            AuthIntent.Submit -> submit()
        }
    }

    private suspend fun submit() {
        val s = currentState
        if (!s.canSubmit) return
        setState { copy(loading = true, error = null) }
        val result = if (s.registerMode) auth.register(s.email, s.password, s.name) else auth.login(s.email, s.password)
        result.fold(
            onSuccess = {
                setState { copy(loading = false, password = "") }
                sendEffect(AuthEffect.SignedIn)
            },
            onFailure = { e -> setState { copy(loading = false, error = e.message ?: "Sign-in failed") } },
        )
    }
}
