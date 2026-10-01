package com.voicecontrol.core.data

import kotlinx.serialization.json.Json

/** Shared JSON configuration for local storage. */
internal val StorageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
