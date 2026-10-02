package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.UserProfile

/** Finds the saved (possibly dashboard-edited) flow that best matches a screen. */
fun interface FlowSource {
    suspend fun flowFor(snapshot: ScreenSnapshot): FlowDefinition?
}

/** Supplies the user's saved profile for pre-filling common fields. */
fun interface ProfileSource {
    suspend fun profile(): UserProfile?
}

/** Persists finished sessions (history) and turns them into flows. */
fun interface SessionRecorder {
    suspend fun record(summary: SessionSummary)
}

/**
 * Vision fallback for apps that expose no accessibility nodes (games, canvas/Flutter-without-semantics UIs):
 * detects fields and buttons on a screenshot. Returned element bounds are in *screen* pixels and ids
 * start with [VISION_ID_PREFIX].
 */
fun interface VisionDetector {
    suspend fun detect(screenshot: Screenshot, packageName: String, language: Language): List<ScreenElement>?

    companion object {
        const val VISION_ID_PREFIX = "vision:"
    }
}

/** Why starting a flow on demand did or didn't work. */
enum class LaunchResult { STARTED, BUSY, SERVICE_OFF, NO_MIC_PERMISSION }

/** Starts a specific flow on this phone (from the app, the dashboard or a trigger). */
fun interface FlowLauncher {
    fun launch(flow: com.voicecontrol.core.model.FlowDefinition): LaunchResult
}

/** Starts "teach by doing": VoiceControl watches the user fill a form by touch and turns it into a flow. */
fun interface TeachLauncher {
    fun startTeaching(): LaunchResult
}

/** Voice shortcuts ("say this to run that flow") and the flows they run. */
interface ShortcutSource {
    suspend fun shortcuts(): List<com.voicecontrol.core.engine.VoiceShortcut>

    /** The flow to run, from the phone's cache or the account. */
    suspend fun flow(flowId: String): FlowDefinition?

    /** Called when a shortcut's flow starts (for the dashboard's run log). */
    suspend fun started(shortcut: com.voicecontrol.core.engine.VoiceShortcut) = Unit
}

/**
 * Answers remembered across sessions (opt-in), per app and field, to offer next time
 * ("Last time you said …"). Never holds password, OTP or PIN values.
 */
interface AnswerMemory {
    suspend fun recall(appPackage: String, key: String): String?
    suspend fun remember(appPackage: String, key: String, label: String, value: String)
}

/** What to ask for one field, and a short explanation for when the user is stuck. */
data class WrittenQuestion(val question: String, val hint: String? = null)

/**
 * Friendlier spoken questions for a screen's fields, written by AI on the server in the user's language.
 * Returns questions by element id; empty or failing means the built-in questions are used.
 */
fun interface QuestionWriter {
    suspend fun write(screen: com.voicecontrol.core.model.ScreenSnapshot, language: com.voicecontrol.core.model.Language): Map<String, WrittenQuestion>
}

/** An app the user can open by voice. */
data class InstalledApp(val label: String, val packageName: String)

/** The phone's launchable apps ("open WhatsApp"). */
fun interface AppDirectory {
    suspend fun apps(): List<InstalledApp>
}

/** What the helper does next while working towards the user's goal ("do it for me"). */
enum class AgentAction { CLICK, FILL, ASK, SCROLL_DOWN, SCROLL_UP, BACK, OPEN_APP, WAIT, DONE, GIVE_UP }

data class AgentDecision(
    val action: AgentAction,
    val targetId: String? = null,
    val value: String? = null,
    /** Short spoken line about what is being done now. */
    val say: String? = null,
    /** ASK: what to ask the user. */
    val question: String? = null,
    val appName: String? = null,
    /** Ask the user before this press (pays, sends, deletes, submits). */
    val confirm: Boolean = false,
)

/**
 * Plans one step at a time towards a goal on the current screen (AI on the server). [history] lists what
 * happened so far, newest last, and never holds sensitive values. Null when unavailable (offline,
 * on-device only, signed out, or no planning model).
 */
fun interface GoalAgent {
    suspend fun next(
        goal: String,
        screen: com.voicecontrol.core.model.ScreenSnapshot,
        history: List<String>,
        language: com.voicecontrol.core.model.Language,
    ): AgentDecision?
}
