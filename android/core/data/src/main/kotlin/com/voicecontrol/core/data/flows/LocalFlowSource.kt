package com.voicecontrol.core.data.flows

import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** Flow lookup used by the engine: on-device matching against stored flows. */
@Singleton
class LocalFlowSource @Inject constructor(
    private val flows: FlowRepository,
) : FlowSource {
    override suspend fun flowFor(snapshot: ScreenSnapshot): FlowDefinition? = flows.localMatch(snapshot)
}
