package com.voicecontrol.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class AuthTokens(val accessToken: String, val refreshToken: String, val accessExpiresAtMillis: Long)

data class SignedInUser(val id: String, val email: String, val name: String?)

/** Encrypted persistence of the signed-in user's tokens. */
@Singleton
class TokenStore @Inject constructor(
    @Named("auth") private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    private object Keys {
        val access = stringPreferencesKey("access")
        val refresh = stringPreferencesKey("refresh")
        val expires = longPreferencesKey("expires_at")
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val name = stringPreferencesKey("name")
    }

    val user: Flow<SignedInUser?> = store.data.map { p ->
        val id = p[Keys.userId] ?: return@map null
        if (p[Keys.refresh] == null) return@map null
        SignedInUser(id, p[Keys.email].orEmpty(), p[Keys.name])
    }

    suspend fun tokens(): AuthTokens? {
        val p = store.data.first()
        val access = p[Keys.access]?.let(cipher::decrypt) ?: return null
        val refresh = p[Keys.refresh]?.let(cipher::decrypt) ?: return null
        return AuthTokens(access, refresh, p[Keys.expires] ?: 0L)
    }

    suspend fun save(tokens: AuthTokens, user: SignedInUser? = null) {
        store.edit { p ->
            p[Keys.access] = cipher.encrypt(tokens.accessToken)
            p[Keys.refresh] = cipher.encrypt(tokens.refreshToken)
            p[Keys.expires] = tokens.accessExpiresAtMillis
            if (user != null) {
                p[Keys.userId] = user.id
                p[Keys.email] = user.email
                if (user.name != null) p[Keys.name] = user.name else p.remove(Keys.name)
            }
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
