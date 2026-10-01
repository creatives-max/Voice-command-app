package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.expr.Expressions
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
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.FlowVariables
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
import com.voicecontrol.core.model.UserProfile
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 *
 * Flows can carry logic: steps run only when their condition holds (else an alternative value is
 * filled), answers are kept in session variables that later conditions, computed values and
 * `{var}` question templates use, REPEAT steps loop over list items, and NEXT_SCREEN / OPEN_APP
 * steps split a flow into screens, possibly across apps; variables carry across screens.
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

    /** Starts a session; with [flow], that flow is run instead of matching one to the screen. */
    fun start(flow: FlowDefinition? = null) {
        if (isActive) return
        job = scope.launch { runSession(flow) }
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
        /** Flow variables: answers, READ results, SET_VARIABLE values. Never sensitive values. */
        val vars = LinkedHashMap<String, String>()
        /** Values already on the current screen, by label slug (lowest precedence). */
        var screenVars: Map<String, String> = emptyMap()
        var profile: UserProfile? = null
        /** Signature of the screen as last seen (loops can change it by adding rows). */
        var currentSignature: String = ""

        fun lookup(name: String): Any? = vars[name] ?: profileVar(name) ?: screenVars[name]

        private fun profileVar(name: String): String? {
            if (!name.startsWith("profile.")) return null
            val key = FlowVariables.profileNames.entries.firstOrNull { it.value == name }?.key ?: return null
            return profile?.value(key)
        }
    }

    /** Per-screen recording. */
    private class ScreenLog(val snapshot: ScreenSnapshot, val flowId: String?, val flowVersion: Int?) {
        val steps = LinkedHashMap<String, StepRecord>()
        /** Set while a REPEAT item runs, so each item's steps are recorded separately. */
        var keySuffix = ""
        fun put(step: PlanStep, outcome: StepOutcome, question: String?, by: String? = null) {
            if (step.virtual) return
            steps[step.elementId + keySuffix] = StepRecord(step.elementId, step.label, step.kind, step.fieldType, question, outcome, by)
        }
        fun putClick(element: ScreenElement) {
            steps[element.id + keySuffix] = StepRecord(element.id, element.label, element.kind, null, null, StepOutcome.CLICKED)
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

    private suspend fun runSession(preselected: FlowDefinition?) {
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
            // A multi-screen (or preselected) flow in progress, and which of its screens is next.
            var active: FlowDefinition? = preselected
            var segment = 0
            preselected?.segments?.firstOrNull()?.firstOrNull()?.takeIf { it.action.isScreenBoundary }?.let { boundary ->
                if (!enterSegment(session, boundary, previousSignature = null)) {
                    say(session, session.phrases.screenNotReached())
                    session.status = RunStatus.FAILED
                    return
                }
            }
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
                val running = active
                val flow = if (running != null) {
                    running.copy(steps = running.segments[segment])
                } else {
                    val matched = runCatching { flows.flowFor(snapshot) }.getOrNull()
                    if (matched != null && matched.segments.size > 1) {
                        active = matched
                        segment = 0
                        matched.copy(steps = matched.segments[0])
                    } else {
                        matched
                    }
                }
                val profile = runCatching { profiles.profile() }.getOrNull()
                session.profile = profile
                session.screenVars = screenVariables(snapshot)
                session.currentSignature = snapshot.signature
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
                val multi = active
                if (multi != null && segment + 1 < multi.segments.size) {
                    // Continue the same flow on its next screen (possibly in another app).
                    segment++
                    if (!enterSegment(session, multi.segments[segment].first(), previousSignature = session.currentSignature)) {
                        say(session, session.phrases.screenNotReached())
                        session.status = RunStatus.FAILED
                        return
                    }
                    say(session, session.phrases.newScreen())
                    continue
                }
                active = null
                if (outcome == ScreenOutcome.COMPLETED) break
                // A button was pressed: wait for the app to react and continue on a new form.
                delay(screenSettleMillis)
                val next = readScreen(session)
                val hasNewForm = next != null && next.signature != session.currentSignature &&
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

    /**
     * Gets to the screen a flow segment starts on: OPEN_APP launches the app and waits for it,
     * NEXT_SCREEN waits until the screen differs from [previousSignature]. Returns false on timeout.
     */
    private suspend fun enterSegment(session: Session, boundary: FlowStep, previousSignature: String?): Boolean {
        val timeoutMs = (boundary.waitSeconds ?: DEFAULT_WAIT_SECONDS).coerceIn(1, MAX_WAIT_SECONDS) * 1_000L
        val pkg = boundary.appPackage?.takeIf { it.isNotBlank() }
        return when (boundary.action) {
            StepAction.OPEN_APP -> {
                if (pkg == null) return false
                if (screen.capture()?.packageName == pkg) return true
                say(session, session.phrases.openingApp(boundary.label.ifBlank { pkg }))
                setStatus(EngineStatus.ACTING)
                if (!perform(ScreenAction.LaunchApp(pkg)).isSuccess) return false
                waitForScreen(timeoutMs) { it.packageName == pkg }
            }
            StepAction.NEXT_SCREEN -> {
                if (previousSignature == null) return true
                setStatus(EngineStatus.THINKING, caption = session.phrases.waitingForScreen())
                waitForScreen(timeoutMs) { snap ->
                    (pkg == null || snap.packageName == pkg) && snap.signature != previousSignature
                }
            }
            else -> true
        }
    }

    private suspend fun waitForScreen(timeoutMs: Long, accept: (ScreenSnapshot) -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            val snap = screen.capture()
            if (snap != null && snap.hasReadableElements && accept(snap)) {
                delay(screenSettleMillis)
                return true
            }
            if (waited >= timeoutMs) return false
            delay(SCREEN_POLL_MS)
            waited += SCREEN_POLL_MS
        }
    }

    /** Non-sensitive values already on screen, available to expressions by label slug. */
    private fun screenVariables(snapshot: ScreenSnapshot): Map<String, String> = buildMap {
        snapshot.elements.forEach { e ->
            if (e.isSensitive || e.label.none { it.isLetterOrDigit() && it.code < 128 }) return@forEach
            val value = when {
                e.kind.isToggle -> e.isChecked?.let { if (it) "yes" else "no" }
                e.kind.isInput -> e.value?.takeIf { it.isNotBlank() }
                else -> null
            } ?: return@forEach
            putIfAbsent(FlowVariables.slug(e.label, 0), value)
        }
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
        if (announce) say(session, phrases.start(plan.steps.count { !it.skip && !it.virtual }))

        when (runSteps(session, snapshot, plan.steps, log, progressPrefix = "")) {
            StepResult.Stop -> return ScreenOutcome.STOPPED
            StepResult.Navigated -> return ScreenOutcome.NAVIGATED
            else -> Unit
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
    // Steps and flow logic

    /**
     * Runs [steps] in order, honouring conditions and "previous". Returns Done when all ran, or the
     * result that ended the run early (Submit, Stop, Navigated).
     */
    private suspend fun runSteps(
        session: Session,
        snapshot: ScreenSnapshot,
        steps: List<PlanStep>,
        log: ScreenLog,
        progressPrefix: String,
    ): StepResult {
        val filledByExtras = mutableSetOf<String>()
        var index = 0
        var goingBack = false
        while (index < steps.size) {
            val step = steps[index]
            _state.update { it.copy(progress = "$progressPrefix${index + 1} / ${steps.size}", helpVideoUrl = step.helpVideoUrl) }
            if (!conditionHolds(session, step)) {
                if (goingBack && index > 0) {
                    index--
                    continue
                }
                goingBack = false
                applyElse(session, step, log)
                index++
                continue
            }
            goingBack = false
            val result = if (step.elementId in filledByExtras) {
                StepResult.Done
            } else {
                runStep(session, snapshot, step, log, filledByExtras)
            }
            when (result) {
                StepResult.Done -> index++
                StepResult.Previous -> {
                    index = (index - 1).coerceAtLeast(0)
                    goingBack = true
                }
                StepResult.Submit, StepResult.Stop, StepResult.Navigated -> return result
            }
        }
        return StepResult.Done
    }

    private suspend fun runStep(
        session: Session,
        snapshot: ScreenSnapshot,
        step: PlanStep,
        log: ScreenLog,
        filledByExtras: MutableSet<String>,
    ): StepResult {
        when (step.action) {
            StepAction.SET_VARIABLE -> {
                val name = step.variable
                val expression = step.valueExpression
                if (name != null && expression != null) evaluateText(session, expression)?.let { session.vars[name] = it }
                return StepResult.Done
            }
            StepAction.REPEAT -> return runRepeat(session, snapshot, step, log)
            StepAction.READ -> {
                val fresh = screen.capture()?.element(step.elementId) ?: step.element
                if (!fresh.isSensitive) remember(session, step, fresh.value?.takeIf { it.isNotBlank() } ?: fresh.label)
                return StepResult.Done
            }
            StepAction.CLICK -> {
                // A conditional button press inside the screen (e.g. "Add nominee"); the plan continues.
                if (press(session, step.element, log)) delay(screenSettleMillis)
                return StepResult.Done
            }
            StepAction.NEXT_SCREEN, StepAction.OPEN_APP -> return StepResult.Done
            StepAction.FILL, StepAction.TOGGLE -> Unit
        }
        val expression = step.valueExpression
        if (expression != null && !step.isSensitive) {
            val value = evaluateText(session, expression)
            if (!value.isNullOrBlank()) {
                setStatus(EngineStatus.ACTING)
                val ok = if (step.action == StepAction.TOGGLE) {
                    perform(ScreenAction.SetChecked(step.elementId, Expressions.truthy(value))).isSuccess
                } else {
                    perform(ScreenAction.SetText(step.elementId, value)).isSuccess
                }
                log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
                if (ok) remember(session, step, if (step.action == StepAction.TOGGLE) yesNo(Expressions.truthy(value)) else value)
                return StepResult.Done
            }
        }
        val templated = if ('{' in step.question) step.copy(question = template(session, step.question)) else step
        return handleStep(session, snapshot, templated, log, filledByExtras)
    }

    /** The "else" branch of a step whose condition is false: fill the alternative value, or skip. */
    private suspend fun applyElse(session: Session, step: PlanStep, log: ScreenLog) {
        if (step.virtual || step.action == StepAction.READ || step.action == StepAction.CLICK) return
        val expression = step.elseValue
        val value = expression?.let { evaluateText(session, it) }
        if (value.isNullOrBlank() || step.isSensitive) {
            log.put(step, StepOutcome.SKIPPED, null)
            return
        }
        setStatus(EngineStatus.ACTING)
        val ok = if (step.action == StepAction.TOGGLE) {
            perform(ScreenAction.SetChecked(step.elementId, Expressions.truthy(value))).isSuccess
        } else {
            perform(ScreenAction.SetText(step.elementId, value)).isSuccess
        }
        log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
        if (ok) remember(session, step, if (step.action == StepAction.TOGGLE) yesNo(Expressions.truthy(value)) else value)
    }

    /** Runs a REPEAT step: its steps once per list item, pressing "add another" between items. */
    private suspend fun runRepeat(session: Session, snapshot: ScreenSnapshot, step: PlanStep, log: ScreenLog): StepResult {
        val spec = step.repeat ?: return StepResult.Done
        val phrases = session.phrases
        val builder = PlanBuilder(phrases)
        val itemName = spec.itemLabel?.takeIf { it.isNotBlank() } ?: step.label.takeIf { it.isNotBlank() } ?: "item"
        val max = spec.maxIterations.coerceIn(1, MAX_REPEAT)
        var current = snapshot
        try {
            for (index in 1..max) {
                session.vars[FlowVariables.INDEX] = index.toString()
                val items = builder.resolveRepeatItem(current, step.repeatBody, index, session.profile)
                if (items.isEmpty()) break
                say(session, phrases.item(itemName, index))
                log.keySuffix = "#$index"
                val result = runSteps(session, current, items, log, progressPrefix = "$itemName $index · ")
                if (result != StepResult.Done) return result
                val more = when (val count = spec.countExpression?.takeIf { it.isNotBlank() }) {
                    null -> askYesNoOr(session, current, phrases.addAnother(itemName), default = false) ?: return StepResult.Stop
                    else -> index < (Expressions.number(evaluate(session, count)) ?: 1.0).toInt()
                }
                if (!more || index == max) break
                val live = screen.capture() ?: current
                val addMore = builder.locate(spec.addMoreElementId, spec.addMoreLabel, live.elements)
                if (addMore != null) {
                    if (!act(session, ScreenAction.Click(addMore.id), null)) break
                    delay(screenSettleMillis)
                }
                current = readScreen(session) ?: break
                session.currentSignature = current.signature
            }
        } finally {
            log.keySuffix = ""
            session.vars.remove(FlowVariables.INDEX)
        }
        return StepResult.Done
    }

    private fun conditionHolds(session: Session, step: PlanStep): Boolean {
        val condition = step.condition ?: return true
        // A broken expression should not silently hide a question: ask it.
        return runCatching { Expressions.evaluateBoolean(condition, session::lookup, today()) }.getOrDefault(true)
    }

    private fun evaluate(session: Session, expression: String): Any? =
        runCatching { Expressions.evaluate(expression, session::lookup, today()) }.getOrNull()

    private fun evaluateText(session: Session, expression: String): String? =
        runCatching { Expressions.evaluateText(expression, session::lookup, today()) }.getOrNull()

    private fun template(session: Session, text: String): String = Expressions.template(text, session::lookup, today())

    private fun today(): () -> LocalDate = { Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate() }

    /** Stores a step's answer in its variable (and `<name>_<index>` inside a loop). Never sensitive values. */
    private fun remember(session: Session, step: PlanStep, value: String) {
        if (step.isSensitive) return
        val name = step.variable ?: return
        session.vars[name] = value
        session.vars[FlowVariables.INDEX]?.let { session.vars["${name}_$it"] = value }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

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
                if (ok) remember(session, step, default)
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
                remember(session, step, element.value!!)
                return StepResult.Done
            }
            val keep = askYesNo(session, snapshot, step, phrases.keepExisting(step.label, element.value!!))
            when (keep) {
                true -> {
                    log.put(step, StepOutcome.KEPT, null)
                    remember(session, step, element.value!!)
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
        remember(session, step, value)
        say(session, if (session.cfg.confirmValues && value.length <= MAX_READBACK) session.phrases.filled(value) else session.phrases.filledShort())
        return StepResult.Done
    }

    private suspend fun toggle(session: Session, step: PlanStep, checked: Boolean, log: ScreenLog, question: String): StepResult {
        val ok = act(session, ScreenAction.SetChecked(step.elementId, checked), null)
        log.put(step, if (ok) StepOutcome.TOGGLED else StepOutcome.FAILED, question)
        if (ok) remember(session, step, yesNo(checked))
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
                remember(session, step, option.label)
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

    /** Yes/no question not tied to a field; [default] when nothing usable was heard, null on "stop". */
    private suspend fun askYesNoOr(session: Session, snapshot: ScreenSnapshot, question: String, default: Boolean): Boolean? {
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            when (interpret(session, snapshot, null, heard, question).intent) {
                IntentKind.YES, IntentKind.NEXT -> return true
                IntentKind.NO, IntentKind.SKIP, IntentKind.SUBMIT -> return false
                IntentKind.STOP -> return null
                else -> say(session, session.phrases.didNotCatch())
            }
        }
        return default
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
        const val DEFAULT_WAIT_SECONDS = 20
        const val MAX_WAIT_SECONDS = 120
        const val SCREEN_POLL_MS = 500L
        const val MAX_REPEAT = 50
    }
}
