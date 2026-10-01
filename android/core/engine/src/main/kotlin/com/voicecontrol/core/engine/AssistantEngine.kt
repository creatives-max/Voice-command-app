package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.engine.port.InterpretRequest
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.ProfileSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SessionConfigProvider
import com.voicecontrol.core.engine.port.SessionRecorder
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.IntentKind
import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenRecord
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.ScrollDirection
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.StepRecord
import com.voicecontrol.core.nlp.FieldValidator
import com.voicecontrol.core.nlp.SpeechNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class EngineStatus { IDLE, STARTING, SPEAKING, LISTENING, THINKING, ACTING, PAUSED, FINISHED, ERROR }

/** Observable state of a voice session, rendered by the overlay. */
data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val active: Boolean = false,
    val caption: String? = null,
    val heard: String? = null,
    val progress: String? = null,
    val helpVideoUrl: String? = null,
    val micLevel: Float = 0f,
    val appPackage: String? = null,
)

/**
 * The voice session state machine.
 *
 * For each screen: build a plan (saved flow + live fields), then for every step ask by voice,
 * listen, interpret (commands locally, answers locally or via the backend), validate, type the
 * value, and finally offer to press the submit button. When pressing a button opens a new screen
 * with fields, the session continues there. Every finished session is recorded (history + flows).
 */
class AssistantEngine(
    private val screen: ScreenGateway,
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val interpreter: Interpreter,
    private val flows: FlowSource,
    private val profiles: ProfileSource,
    private val recorder: SessionRecorder,
    private val config: SessionConfigProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val screenSettleMillis: Long = 1_200L,
    private val vision: VisionDetector? = null,
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var job: Job? = null
    private val localCommands = LocalInterpreter()

    val isActive: Boolean get() = job?.isActive == true

    fun toggle() = if (isActive) stop() else start()

    fun start() {
        if (isActive) return
        job = scope.launch { runSession() }
    }

    fun stop() {
        stt.cancel()
        tts.stop()
        job?.cancel(UserStop())
    }

    // ---------------------------------------------------------------------------------------------
    // Session

    private class UserStop : CancellationException("stopped by user")

    private sealed interface StepResult {
        data object Done : StepResult
        data object Previous : StepResult
        data object Submit : StepResult
        data object Stop : StepResult
        /** A button/back was pressed; the screen may have changed. */
        data object Navigated : StepResult
    }

    private enum class ScreenOutcome { COMPLETED, NAVIGATED, STOPPED }

    /** Mutable context for one running session. */
    private inner class Session(val cfg: SessionConfig) {
        val id = newId()
        val startedAt = clock()
        val phrases = Phrases(cfg.language)
        val screens = mutableListOf<ScreenRecord>()
        var appPackage: String = ""
        var status = RunStatus.COMPLETED
        var silentFailures = 0
    }

    /** Per-screen recording. */
    private class ScreenLog(val snapshot: ScreenSnapshot, val flowId: String?, val flowVersion: Int?) {
        val steps = LinkedHashMap<String, StepRecord>()
        fun put(step: PlanStep, outcome: StepOutcome, question: String?, by: String? = null) {
            steps[step.elementId] = StepRecord(step.elementId, step.label, step.kind, step.fieldType, question, outcome, by)
        }
        fun putClick(element: ScreenElement) {
            steps[element.id] = StepRecord(element.id, element.label, element.kind, null, null, StepOutcome.CLICKED)
        }
        fun toRecord() = ScreenRecord(
            appPackage = snapshot.packageName,
            activityName = snapshot.activityName,
            screenTitle = snapshot.title,
            screenSignature = snapshot.signature,
            flowId = flowId,
            flowVersion = flowVersion,
            steps = steps.values.toList(),
        )
    }

    private suspend fun runSession() {
        val cfg = config.current()
        val session = Session(cfg)
        _state.value = EngineState(status = EngineStatus.STARTING, active = true)
        try {
            if (!screen.isAvailable.value) {
                say(session, session.phrases.serviceOff())
                session.status = RunStatus.FAILED
                return
            }
            var first = true
            var visited = 0
            while (visited < MAX_SCREENS) {
                visited++
                val snapshot = readScreen(session)
                if (snapshot == null || !snapshot.hasReadableElements) {
                    say(session, session.phrases.cannotRead())
                    session.status = RunStatus.FAILED
                    return
                }
                session.appPackage = snapshot.packageName
                _state.update { it.copy(appPackage = snapshot.packageName) }
                setStatus(EngineStatus.THINKING, caption = null)
                val flow = runCatching { flows.flowFor(snapshot) }.getOrNull()
                val profile = runCatching { profiles.profile() }.getOrNull()
                val plan = PlanBuilder(session.phrases).build(snapshot, flow, profile)
                val log = ScreenLog(snapshot, plan.flowId, plan.flowVersion)
                val outcome = try {
                    runScreen(session, snapshot, plan, log, announce = first)
                } finally {
                    if (log.steps.isNotEmpty()) session.screens += log.toRecord()
                }
                first = false
                if (outcome == ScreenOutcome.STOPPED) {
                    say(session, session.phrases.stopped())
                    session.status = RunStatus.STOPPED
                    return
                }
                if (outcome == ScreenOutcome.COMPLETED) break
                // A button was pressed: wait for the app to react and continue on a new form.
                delay(screenSettleMillis)
                val next = readScreen(session)
                val hasNewForm = next != null && next.signature != snapshot.signature &&
                    next.elements.any { it.kind.isInput || it.kind.isToggle }
                if (!hasNewForm) break
                say(session, session.phrases.newScreen())
            }
            say(session, session.phrases.done())
        } catch (e: UserStop) {
            session.status = RunStatus.STOPPED
        } catch (e: CancellationException) {
            session.status = RunStatus.STOPPED
            throw e
        } catch (e: Exception) {
            session.status = RunStatus.FAILED
            _state.update { it.copy(status = EngineStatus.ERROR, caption = e.message ?: session.phrases.actionFailed()) }
        } finally {
            withContext(NonCancellable) {
                finish(session)
            }
        }
    }

    /**
     * Reads the screen through accessibility; if nothing readable is exposed and vision fallback is on,
     * detects elements on a screenshot instead (those elements are then operated with taps).
     */
    private suspend fun readScreen(session: Session): ScreenSnapshot? {
        val snapshot = screen.capture()
        visionElements = emptyMap()
        if (snapshot != null && snapshot.hasReadableElements) return snapshot
        val detector = vision ?: return snapshot
        if (!session.cfg.visionFallback || session.cfg.localOnly) return snapshot
        setStatus(EngineStatus.THINKING, caption = session.phrases.lookingAtScreen())
        val shot = screen.screenshot() ?: return snapshot
        val pkg = snapshot?.packageName ?: _state.value.appPackage ?: ""
        val elements = runCatching { detector.detect(shot, pkg, session.cfg.language) }.getOrNull()
            ?.filter { it.id.startsWith(VisionDetector.VISION_ID_PREFIX) && !it.bounds.isEmpty }
            .orEmpty()
        if (elements.isEmpty()) return snapshot
        visionElements = elements.associateBy { it.id }
        val base = ScreenSnapshot(packageName = pkg, activityName = snapshot?.activityName, title = snapshot?.title, elements = elements, capturedAtMillis = clock())
        return base.copy(signature = com.voicecontrol.core.screen.ScreenSignature.of(base))
    }

    /** Elements detected by vision on the current screen, operated by coordinates. */
    @Volatile
    private var visionElements: Map<String, ScreenElement> = emptyMap()

    /** Routes actions on vision-detected elements to taps/typing; everything else goes to the gateway. */
    private suspend fun perform(action: ScreenAction): ActionResult {
        val elementId = when (action) {
            is ScreenAction.SetText -> action.elementId
            is ScreenAction.Click -> action.elementId
            is ScreenAction.SetChecked -> action.elementId
            is ScreenAction.Focus -> action.elementId
            else -> null
        }
        val target = elementId?.let { visionElements[it] } ?: return screen.perform(action)
        val tap = ScreenAction.TapAt(target.bounds.centerX, target.bounds.centerY)
        return when (action) {
            is ScreenAction.SetText -> {
                val tapped = screen.perform(tap)
                if (!tapped.isSuccess) return tapped
                delay(VISION_FOCUS_DELAY_MS)
                screen.perform(ScreenAction.TypeIntoFocused(action.text))
            }
            else -> screen.perform(tap)
        }
    }

    private suspend fun finish(session: Session) {
        val summary = SessionSummary(
            sessionId = session.id,
            appPackage = session.appPackage,
            startedAtMillis = session.startedAt,
            endedAtMillis = clock(),
            status = session.status,
            language = session.cfg.language,
            screens = session.screens.toList(),
        )
        if (summary.screens.isNotEmpty()) runCatching { recorder.record(summary) }
        _state.update {
            it.copy(
                status = if (session.status == RunStatus.FAILED) EngineStatus.ERROR else EngineStatus.FINISHED,
                active = false,
                heard = null,
                progress = null,
                helpVideoUrl = null,
                micLevel = 0f,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Screen

    private suspend fun runScreen(
        session: Session,
        snapshot: ScreenSnapshot,
        plan: ScreenPlan,
        log: ScreenLog,
        announce: Boolean,
    ): ScreenOutcome {
        val phrases = session.phrases
        if (plan.steps.isEmpty()) return commandMode(session, snapshot, log)
        if (announce) say(session, phrases.start(plan.steps.count { !it.skip }))

        var index = 0
        val filledByExtras = mutableSetOf<String>()
        while (index < plan.steps.size) {
            val step = plan.steps[index]
            _state.update { it.copy(progress = "${index + 1} / ${plan.steps.size}", helpVideoUrl = step.helpVideoUrl) }
            val result = if (step.elementId in filledByExtras) {
                StepResult.Done
            } else {
                handleStep(session, snapshot, step, log, filledByExtras)
            }
            when (result) {
                StepResult.Done -> index++
                StepResult.Previous -> index = (index - 1).coerceAtLeast(0)
                StepResult.Submit -> break
                StepResult.Stop -> return ScreenOutcome.STOPPED
                StepResult.Navigated -> return ScreenOutcome.NAVIGATED
            }
        }
        _state.update { it.copy(progress = null, helpVideoUrl = null) }
        return submit(session, snapshot, plan, log)
    }

    /** Screens without fields: let the user press buttons, scroll or go back by voice. */
    private suspend fun commandMode(session: Session, snapshot: ScreenSnapshot, log: ScreenLog): ScreenOutcome {
        val phrases = session.phrases
        val buttons = snapshot.buttons.filter { it.isEnabled }.take(MAX_BUTTONS_SPOKEN).map { it.label }
        var prompt = if (buttons.isEmpty()) phrases.nothingToFill() else phrases.whichButton(buttons)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, prompt) ?: return@repeat
            val interp = interpret(session, snapshot, null, heard, prompt)
            when (interp.intent) {
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return ScreenOutcome.NAVIGATED
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return ScreenOutcome.NAVIGATED
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> {
                    scroll(session, interp.intent)
                    return ScreenOutcome.NAVIGATED
                }
                IntentKind.STOP, IntentKind.NO -> return ScreenOutcome.STOPPED
                IntentKind.HELP -> prompt = phrases.help()
                else -> prompt = phrases.didNotCatch() + " " + prompt
            }
        }
        return ScreenOutcome.STOPPED
    }

    private suspend fun submit(session: Session, snapshot: ScreenSnapshot, plan: ScreenPlan, log: ScreenLog): ScreenOutcome {
        val button = plan.submitButton ?: return ScreenOutcome.COMPLETED
        val phrases = session.phrases
        if (plan.autoSubmit || !session.cfg.askBeforeSubmit) {
            return if (press(session, button, log)) ScreenOutcome.NAVIGATED else ScreenOutcome.COMPLETED
        }
        val question = plan.submitQuestion ?: phrases.confirmPress(button.label)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            val interp = interpret(session, snapshot, null, heard, question)
            when (interp.intent) {
                IntentKind.YES, IntentKind.SUBMIT, IntentKind.NEXT ->
                    return if (press(session, button, log)) ScreenOutcome.NAVIGATED else ScreenOutcome.COMPLETED
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return ScreenOutcome.NAVIGATED
                IntentKind.NO, IntentKind.SKIP -> return ScreenOutcome.COMPLETED
                IntentKind.STOP -> return ScreenOutcome.STOPPED
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return ScreenOutcome.NAVIGATED
                }
                else -> Unit
            }
        }
        return ScreenOutcome.COMPLETED
    }

    // ---------------------------------------------------------------------------------------------
    // Step

    private suspend fun handleStep(
        session: Session,
        snapshot: ScreenSnapshot,
        step: PlanStep,
        log: ScreenLog,
        filledByExtras: MutableSet<String>,
    ): StepResult {
        val phrases = session.phrases
        val element = step.element

        if (step.skip) {
            val default = step.defaultValue
            if (default != null && step.action == StepAction.FILL && !step.isSensitive) {
                val ok = perform(ScreenAction.SetText(step.elementId, default)).isSuccess
                log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
            } else {
                log.put(step, StepOutcome.SKIPPED, null)
            }
            return StepResult.Done
        }

        if (step.isSensitive) return handleSensitive(session, snapshot, step, log)

        var question = step.question
        if (!element.value.isNullOrBlank() && element.kind == ElementKind.TEXT_FIELD) {
            if (session.cfg.skipFilledFields) {
                log.put(step, StepOutcome.KEPT, null)
                return StepResult.Done
            }
            val keep = askYesNo(session, snapshot, step, phrases.keepExisting(step.label, element.value!!))
            when (keep) {
                true -> {
                    log.put(step, StepOutcome.KEPT, null)
                    return StepResult.Done
                }
                null -> return StepResult.Stop
                false -> Unit
            }
        }
        var suggestion = step.suggestedValue
        if (suggestion != null) question = phrases.askUseSuggested(question, suggestion)

        if (step.kind == ElementKind.DROPDOWN) return handleDropdown(session, step, question, log)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            val heard = askAndListen(session, question)
            if (heard == null) {
                attempts++
                continue
            }
            val interp = interpret(session, snapshot, step.elementId, heard, question)
            when (interp.intent) {
                IntentKind.NEXT, IntentKind.SKIP -> {
                    log.put(step, StepOutcome.SKIPPED, question)
                    return StepResult.Done
                }
                IntentKind.PREVIOUS -> return StepResult.Previous
                IntentKind.REPEAT -> Unit
                IntentKind.HELP -> say(session, phrases.help())
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SUBMIT -> return StepResult.Submit
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return StepResult.Navigated
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> scroll(session, interp.intent)
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return StepResult.Navigated
                IntentKind.CLEAR -> act(session, ScreenAction.SetText(step.elementId, ""), phrases.cleared(step.label))
                IntentKind.YES -> when {
                    step.action == StepAction.TOGGLE -> return toggle(session, step, true, log, question)
                    suggestion != null -> {
                        fill(session, step, suggestion, log, question, interp.source, emptyList(), filledByExtras)?.let { return it }
                        attempts++
                    }
                    else -> attempts++
                }
                IntentKind.NO -> when {
                    step.action == StepAction.TOGGLE -> return toggle(session, step, false, log, question)
                    suggestion != null -> {
                        suggestion = null
                        question = step.question
                    }
                    else -> attempts++
                }
                IntentKind.FILL -> {
                    if (step.action == StepAction.TOGGLE) {
                        attempts++
                        continue
                    }
                    val target = interp.targetId?.let(snapshot::element)?.takeIf { !it.isSensitive } ?: element
                    val value = interp.value ?: SpeechNormalizer.normalize(heard, target.fieldType, session.cfg.transliterate)
                    if (target.id != step.elementId) {
                        // The user answered a different field ("my phone is …"); fill it and keep asking this one.
                        if (perform(ScreenAction.SetText(target.id, value)).isSuccess) filledByExtras += target.id
                        continue
                    }
                    val validation = FieldValidator.validate(value, step.fieldType, step.rules)
                    if (validation is FieldValidator.Result.Invalid) {
                        say(session, phrases.invalid(validation.message))
                        attempts++
                        continue
                    }
                    val result = fill(session, step, value, log, question, interp.source, interp.extraFills.map { it.targetId to it.value }, filledByExtras, snapshot)
                    if (result != null) return result
                    attempts++
                }
                IntentKind.UNKNOWN -> {
                    say(session, phrases.didNotCatch())
                    attempts++
                }
            }
        }
        log.put(step, StepOutcome.SKIPPED, question)
        return StepResult.Done
    }

    /** Fills the step's field; returns Done on success or null when typing failed. */
    private suspend fun fill(
        session: Session,
        step: PlanStep,
        value: String,
        log: ScreenLog,
        question: String,
        source: String,
        extras: List<Pair<String, String>>,
        filledByExtras: MutableSet<String>,
        snapshot: ScreenSnapshot? = null,
    ): StepResult? {
        setStatus(EngineStatus.ACTING)
        val result = perform(ScreenAction.SetText(step.elementId, value))
        if (result is ActionResult.Failure) {
            say(session, session.phrases.actionFailed())
            return null
        }
        for ((id, extraValue) in extras) {
            val target = snapshot?.element(id) ?: continue
            if (target.isSensitive || id == step.elementId) continue
            if (perform(ScreenAction.SetText(id, extraValue)).isSuccess) filledByExtras += id
        }
        log.put(step, StepOutcome.FILLED, question, source)
        say(session, if (session.cfg.confirmValues && value.length <= MAX_READBACK) session.phrases.filled(value) else session.phrases.filledShort())
        return StepResult.Done
    }

    private suspend fun toggle(session: Session, step: PlanStep, checked: Boolean, log: ScreenLog, question: String): StepResult {
        val ok = act(session, ScreenAction.SetChecked(step.elementId, checked), null)
        log.put(step, if (ok) StepOutcome.TOGGLED else StepOutcome.FAILED, question)
        return StepResult.Done
    }

    private suspend fun handleSensitive(session: Session, snapshot: ScreenSnapshot, step: PlanStep, log: ScreenLog): StepResult {
        perform(ScreenAction.Focus(step.elementId))
        val prompt = session.phrases.sensitiveManual(step.label)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, prompt) ?: return@repeat
            // Never send what was said here anywhere: only local command parsing.
            val command = localCommands.commandOf(InterpretRequest(snapshot.redacted(), null, heard, session.cfg.language, null))
            when (command?.intent) {
                IntentKind.NEXT, IntentKind.YES, IntentKind.SKIP -> {
                    log.put(step, StepOutcome.MANUAL, null)
                    return StepResult.Done
                }
                IntentKind.PREVIOUS -> return StepResult.Previous
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SUBMIT -> return StepResult.Submit
                else -> Unit
            }
        }
        log.put(step, StepOutcome.MANUAL, null)
        return StepResult.Done
    }

    private suspend fun handleDropdown(session: Session, step: PlanStep, question: String, log: ScreenLog): StepResult {
        act(session, ScreenAction.Click(step.elementId), null)
        delay(DROPDOWN_OPEN_MS)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            val options = screen.capture() ?: return@repeat
            val command = localCommands.commandOf(InterpretRequest(options.redacted(), null, heard, session.cfg.language, question))
            when (command?.intent) {
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SKIP, IntentKind.NEXT -> {
                    act(session, ScreenAction.Back, null)
                    log.put(step, StepOutcome.SKIPPED, question)
                    return StepResult.Done
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> {
                    scroll(session, command.intent)
                    return@repeat
                }
                else -> Unit
            }
            val option = ButtonMatcher.find(SpeechNormalizer.stripLeadIns(heard), options.elements)
            if (option != null && act(session, ScreenAction.Click(option.id), session.phrases.filled(option.label))) {
                log.put(step, StepOutcome.FILLED, question)
                return StepResult.Done
            }
            say(session, session.phrases.didNotCatch())
        }
        log.put(step, StepOutcome.SKIPPED, question)
        return StepResult.Done
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    /** Speaks the question, then listens. Returns the transcript or null if nothing usable was heard. */
    private suspend fun askAndListen(session: Session, question: String): String? {
        say(session, question)
        setStatus(EngineStatus.LISTENING, caption = question)
        val result = stt.listen(
            ListenRequest(session.cfg.language.speechTag),
            onPartial = { partial -> _state.update { it.copy(heard = partial) } },
            onLevel = { level -> _state.update { it.copy(micLevel = level) } },
        )
        _state.update { it.copy(micLevel = 0f) }
        return when (result) {
            is ListenResult.Heard -> {
                session.silentFailures = 0
                _state.update { it.copy(heard = result.text) }
                result.text.takeIf { it.isNotBlank() }
            }
            ListenResult.NoMatch -> {
                noSpeech(session)
                null
            }
            is ListenResult.Error -> {
                if (!result.recoverable) throw IllegalStateException(result.message)
                noSpeech(session)
                null
            }
        }
    }

    private suspend fun noSpeech(session: Session) {
        session.silentFailures++
        if (session.silentFailures >= MAX_SILENT_FAILURES) {
            say(session, session.phrases.pausedNoSpeech())
            throw UserStop()
        }
        say(session, session.phrases.didNotCatch())
    }

    private suspend fun askYesNo(session: Session, snapshot: ScreenSnapshot, step: PlanStep, question: String): Boolean? {
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            when (interpret(session, snapshot, step.elementId, heard, question).intent) {
                IntentKind.YES, IntentKind.NEXT, IntentKind.SKIP -> return true
                IntentKind.NO, IntentKind.CLEAR -> return false
                IntentKind.STOP -> return null
                else -> Unit
            }
        }
        return true
    }

    private suspend fun interpret(
        session: Session,
        snapshot: ScreenSnapshot,
        fieldId: String?,
        utterance: String,
        question: String?,
    ): Interpretation {
        setStatus(EngineStatus.THINKING)
        val request = InterpretRequest(snapshot.redacted(), fieldId, utterance, session.cfg.language, question, session.cfg.transliterate)
        return localCommands.commandOf(request)
            ?: runCatching { interpreter.interpret(request) }.getOrElse { localCommands.interpret(request) }
    }

    private suspend fun clickTarget(session: Session, snapshot: ScreenSnapshot, interp: Interpretation, log: ScreenLog): Boolean {
        val target = interp.targetId?.let(snapshot::element)
        if (target == null) {
            say(session, session.phrases.buttonNotFound(interp.value ?: ""))
            return false
        }
        return press(session, target, log)
    }

    private suspend fun press(session: Session, button: ScreenElement, log: ScreenLog): Boolean {
        val ok = act(session, ScreenAction.Click(button.id), session.phrases.pressed(button.label))
        if (ok) log.putClick(button)
        return ok
    }

    private suspend fun scroll(session: Session, intent: IntentKind) {
        val direction = if (intent == IntentKind.SCROLL_UP) ScrollDirection.UP else ScrollDirection.DOWN
        act(session, ScreenAction.Scroll(direction), session.phrases.scrolled())
    }

    private suspend fun act(session: Session, action: ScreenAction, successPhrase: String?): Boolean {
        setStatus(EngineStatus.ACTING)
        val result = perform(action)
        if (result.isSuccess) {
            successPhrase?.let { say(session, it) }
        } else {
            say(session, session.phrases.actionFailed())
        }
        return result.isSuccess
    }

    private suspend fun say(session: Session, text: String) {
        setStatus(EngineStatus.SPEAKING, caption = text)
        tts.speak(text, session.cfg.language.voiceTag, session.cfg.speechRate)
    }

    private fun setStatus(status: EngineStatus, caption: String? = _state.value.caption) {
        _state.update {
            it.copy(
                status = status,
                caption = caption,
                heard = if (status == EngineStatus.LISTENING) null else it.heard,
            )
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_SILENT_FAILURES = 4
        const val MAX_SCREENS = 12
        const val MAX_BUTTONS_SPOKEN = 6
        const val MAX_READBACK = 40
        const val DROPDOWN_OPEN_MS = 600L
        const val VISION_FOCUS_DELAY_MS = 400L
    }
}
