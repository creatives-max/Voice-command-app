package com.voicecontrol.domain.flow

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
enum class StepAction { FILL, CLICK, TOGGLE }

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
)

@Serializable
enum class VersionSource { DEVICE, DASHBOARD, ROLLBACK }

data class Flow(
    val id: UUID,
    val userId: UUID,
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val currentVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

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

interface FlowRepository {
    suspend fun create(flow: Flow, firstVersion: FlowVersion): FlowWithVersion
    suspend fun find(userId: UUID, flowId: UUID): FlowWithVersion?
    suspend fun findBySignature(userId: UUID, appPackage: String, signature: String): FlowWithVersion?
    suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Flow>
    suspend fun versions(userId: UUID, flowId: UUID): List<FlowVersion>
    suspend fun version(userId: UUID, flowId: UUID, version: Int): FlowVersion?
    /**
     * Appends [next] as the new current version if the flow is still at [expectedVersion].
     * Returns null on a concurrent modification.
     */
    suspend fun addVersion(userId: UUID, flowId: UUID, expectedVersion: Int, name: String?, next: FlowVersion): FlowWithVersion?
    suspend fun delete(userId: UUID, flowId: UUID): Boolean
    suspend fun apps(userId: UUID): List<AppSummary>
}

data class AppSummary(val appPackage: String, val flowCount: Int, val lastUpdated: Instant)
