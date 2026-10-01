package com.voicecontrol.core.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 * The user's reusable details (name, email, phone, address…). Stored on-device and synced with the
 * backend profile when signed in; local edits are marked dirty until the sync worker pushes them.
 */
@Singleton
class ProfileRepository @Inject constructor(
    @Named("settings") private val store: DataStore<Preferences>,
) : ProfileSource {

    private val key = stringPreferencesKey("profile_json")
    private val dirtyKey = booleanPreferencesKey("profile_dirty")

    val profile: Flow<UserProfile> = store.data.map { p ->
        p[key]?.let { runCatching { StorageJson.decodeFromString(UserProfile.serializer(), it) }.getOrNull() } ?: UserProfile()
    }

    override suspend fun profile(): UserProfile? = profile.first().takeIf { it != UserProfile() }

    /** Saves a local edit; it will be pushed to the server by the next sync. */
    suspend fun save(profile: UserProfile) {
        store.edit {
            it[key] = StorageJson.encodeToString(UserProfile.serializer(), profile)
            it[dirtyKey] = true
        }
    }

    /** Stores the server copy (after a pull or a successful push). */
    suspend fun saveSynced(profile: UserProfile) {
        store.edit {
            it[key] = StorageJson.encodeToString(UserProfile.serializer(), profile)
            it[dirtyKey] = false
        }
    }

    suspend fun isDirty(): Boolean = store.data.first()[dirtyKey] == true

    suspend fun clear() {
        store.edit {
            it.remove(key)
            it.remove(dirtyKey)
        }
    }
}
