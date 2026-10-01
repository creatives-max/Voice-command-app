package com.voicecontrol.domain.flow

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Mirrors the Android model: element steps, then logic steps (no element). */
@Serializable
enum class StepAction {
    FILL, CLICK, TOGGLE, READ, SET_VARIABLE, REPEAT, NEXT_SCREEN, OPEN_APP;

    val targetsElement: Boolean get() = this == FILL || this == CLICK || this == TOGGLE || this == READ
    val isScreenBoundary: Boolean get() = this == NEXT_SCREEN || this == OPEN_APP
}

/** A loop over [stepIds] for lists; see the Android `RepeatSpec`. */
@Serializable
data class RepeatSpec(
    val stepIds: List<String> = emptyList(),
    val addMoreElementId: String? = null,
    val addMoreLabel: String? = null,
    val maxIterations: Int = 10,
    val countExpression: String? = null,
    val itemLabel: String? = null,
)

@Serializable
enum class ProfileKey { FULL_NAME, FIRST_NAME, LAST_NAME, EMAIL, PHONE, ADDRESS_LINE, CITY, STATE, PINCODE, DATE_OF_BIRTH }

/** One step of a flow; editable in the dashboard (question, rules, default, skip, order, help video). */
@Serializable
data class FlowStep(
    val id: String,
    val order: Int,
    val elementId: String,
    val label: String,
    val kind: ElementKind,
    val fieldType: FieldType? = null,
    val action: StepAction = StepAction.FILL,
    val question: String? = null,
    val rules: List<String> = emptyList(),
    val defaultValue: String? = null,
    val skip: Boolean = false,
    val helpVideoUrl: String? = null,
    val profileKey: ProfileKey? = null,
    /** Run only when this expression is true. */
    val condition: String? = null,
    /** Expression filled when [condition] is false. */
    val elseValue: String? = null,
    /** Variable receiving the answer (default: snake-case label). */
    val variable: String? = null,
    /** Expression filled/stored without asking. */
    val valueExpression: String? = null,
    val repeat: RepeatSpec? = null,
    /** OPEN_APP target app, or the app NEXT_SCREEN expects. */
    val appPackage: String? = null,
    val waitSeconds: Int? = null,
)

@Serializable
/** Where a version came from. RECORDED = taught on the phone by demonstration ("teach by doing"). */
enum class VersionSource { DEVICE, DASHBOARD, ROLLBACK, IMPORT, RECORDED }

data class Flow(
    val id: UUID,
    val userId: UUID,
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val currentVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Marketplace listing this flow was imported from, and which of its versions. */
    val sourcePublishedId: UUID? = null,
    val sourceVersion: Int? = null,
    /** Organization that owns the flow; null for a personal flow. */
    val orgId: UUID? = null,
)

/** Whose flows a list covers: the user's personal flows or an organization's. */
sealed interface FlowOwner {
    data class Personal(val userId: UUID) : FlowOwner
    data class Org(val orgId: UUID) : FlowOwner
}

data class FlowVersion(
    val flowId: UUID,
    val version: Int,
    val steps: List<FlowStep>,
    val screenSignature: String,
    val source: VersionSource,
    val changeNote: String?,
    val createdAt: Instant,
)

data class FlowWithVersion(val flow: Flow, val version: FlowVersion)

/**
 * Flows a user can access are their personal flows plus the flows of organizations they belong to.
 * Methods taking a `userId` and a flow id apply that access rule; role checks are the service's job.
 */
interface FlowRepository {
    suspend fun create(flow: Flow, firstVersion: FlowVersion): FlowWithVersion
    suspend fun find(userId: UUID, flowId: UUID): FlowWithVersion?
    /** Personal flow with this exact screen signature (idempotent device uploads). */
    suspend fun findBySignature(userId: UUID, appPackage: String, signature: String): FlowWithVersion?
    /** Any accessible flow with this exact signature, personal first (screen matching). */
    suspend fun findAccessibleBySignature(userId: UUID, appPackage: String, signature: String): FlowWithVersion?
    suspend fun list(owner: FlowOwner, appPackage: String?, limit: Int, offset: Int): List<Flow>
    suspend fun versions(userId: UUID, flowId: UUID): List<FlowVersion>
    suspend fun version(userId: UUID, flowId: UUID, version: Int): FlowVersion?
    /** A version regardless of access (background processing such as embeddings). */
    suspend fun versionById(flowId: UUID, version: Int): FlowVersion?
    /**
     * Appends [next] as the new current version if the flow is still at [expectedVersion].
     * Returns null on a concurrent modification.
     */
    suspend fun addVersion(userId: UUID, flowId: UUID, expectedVersion: Int, name: String?, next: FlowVersion): FlowWithVersion?
    suspend fun delete(userId: UUID, flowId: UUID): Boolean
    suspend fun apps(owner: FlowOwner): List<AppSummary>
    /** Moves a flow to an organization, or back to [personalUserId]'s personal flows when [orgId] is null. */
    suspend fun transfer(flowId: UUID, orgId: UUID?, personalUserId: UUID): Boolean
    /** Records which marketplace listing (and version) a flow now follows. */
    suspend fun linkSource(userId: UUID, flowId: UUID, publishedId: UUID?, version: Int?): Boolean
}

data class AppSummary(val appPackage: String, val flowCount: Int, val lastUpdated: Instant)
