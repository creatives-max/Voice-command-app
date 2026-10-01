package com.voicecontrol.core.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voicecontrol.core.data.StorageJson
import com.voicecontrol.core.engine.port.ProfileSource
import com.voicecontrol.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The user's reusable details (name, email, phone, address…). Stored on-device; synced to the
 * backend profile when signed in (see `SyncManager`).
 */
@Singleton
class ProfileRepository @Inject constructor(
    @Named("settings") private val store: DataStore<Preferences>,
) : ProfileSource {

    private val key = stringPreferencesKey("profile_json")

    val profile: Flow<UserProfile> = store.data.map { p ->
        p[key]?.let { runCatching { StorageJson.decodeFromString(UserProfile.serializer(), it) }.getOrNull() } ?: UserProfile()
    }

    override suspend fun profile(): UserProfile? = profile.first().takeIf { it != UserProfile() }

    suspend fun save(profile: UserProfile) {
        store.edit { it[key] = StorageJson.encodeToString(UserProfile.serializer(), profile) }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
