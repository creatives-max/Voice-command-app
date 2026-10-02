package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.Screenshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Port to the device screen: read it, act on it, and take screenshots for vision fallback. */
interface ScreenGateway {
    val isAvailable: StateFlow<Boolean>

    /** Emits each time the foreground screen meaningfully changes (new signature). */
    val screenChanges: Flow<ScreenSnapshot>

    suspend fun capture(): ScreenSnapshot?

    suspend fun perform(action: ScreenAction): ActionResult

    /** Downscaled screenshot of the current screen, or null if unsupported (API < 30) or denied. */
    suspend fun screenshot(): Screenshot?
}

/** What the user did by touch in another app (only reported while VoiceControl is recording). */
enum class InteractionKind { TYPED, PRESSED }

/**
 * [screen] is the screen the touch happened on, read before the app reacted; a press that opens another
 * screen still belongs to the screen it was made on.
 */
data class UserInteraction(
    val kind: InteractionKind,
    val elementId: String,
    val screen: com.voicecontrol.core.model.ScreenSnapshot? = null,
)

/** Touch and typing events from the foreground app, matched to elements of the current screen. */
interface InteractionSource {
    val interactions: kotlinx.coroutines.flow.Flow<UserInteraction>
}
