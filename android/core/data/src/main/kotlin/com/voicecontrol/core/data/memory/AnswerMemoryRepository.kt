package com.voicecontrol.core.data.memory

import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.AnswerMemory
import com.voicecontrol.core.network.NetworkJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** An answer kept on this phone to offer next time. */
@Serializable
data class RememberedAnswer(val appPackage: String, val key: String, val label: String, val value: String, val updatedAtMillis: Long)

/**
 * "Remember my answers": the latest answer per app and field, kept only on this phone (never synced),
 * newest first and capped at [MAX]. Password, OTP and PIN fields are never stored.
 */
@Singleton
class AnswerMemoryRepository @Inject constructor(private val settings: SettingsRepository) : AnswerMemory {
    private val serializer = ListSerializer(RememberedAnswer.serializer())
    private val lock = Mutex()
    private var clock: () -> Long = System::currentTimeMillis

    val answers: Flow<List<RememberedAnswer>> = settings.rememberedAnswersJson.map(::decode)

    private fun decode(json: String?): List<RememberedAnswer> =
        json?.let { runCatching { NetworkJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    override suspend fun recall(appPackage: String, key: String): String? =
        answers.first().firstOrNull { it.appPackage == appPackage && it.key == key }?.value

    override suspend fun remember(appPackage: String, key: String, label: String, value: String) {
        if (SENSITIVE.any { key.startsWith("$it:") } || value.isBlank() || value.length > MAX_VALUE) return
        lock.withLock {
            val rest = answers.first().filterNot { it.appPackage == appPackage && it.key == key }
            save(listOf(RememberedAnswer(appPackage, key, label.take(MAX_LABEL), value, clock())) + rest)
        }
    }

    suspend fun forget(appPackage: String, key: String) = lock.withLock {
        save(answers.first().filterNot { it.appPackage == appPackage && it.key == key })
    }

    suspend fun forgetAll() = lock.withLock { save(emptyList()) }

    private suspend fun save(list: List<RememberedAnswer>) {
        settings.saveRememberedAnswersJson(NetworkJson.encodeToString(serializer, list.sortedByDescending { it.updatedAtMillis }.take(MAX)))
    }

    companion object {
        const val MAX = 300
        private const val MAX_VALUE = 300
        private const val MAX_LABEL = 80
        private val SENSITIVE = setOf("PASSWORD", "OTP", "PIN")
    }
}
