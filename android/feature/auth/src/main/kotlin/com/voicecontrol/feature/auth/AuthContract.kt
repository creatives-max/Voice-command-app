package com.voicecontrol.feature.auth

data class AuthState(
    val registerMode: Boolean = false,
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !loading && email.contains('@') && password.length >= (if (registerMode) 8 else 1)
}

sealed interface AuthIntent {
    data class EmailChanged(val value: String) : AuthIntent
    data class PasswordChanged(val value: String) : AuthIntent
    data class NameChanged(val value: String) : AuthIntent
    data object ToggleMode : AuthIntent
    data object Submit : AuthIntent
}

sealed interface AuthEffect {
    data object SignedIn : AuthEffect
}
