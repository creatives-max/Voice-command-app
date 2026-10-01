package com.voicecontrol.feature.assistant

import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.Screenshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeScreenGateway(var snapshot: ScreenSnapshot? = null) : ScreenGateway {
    val performed = mutableListOf<ScreenAction>()
    var nextResult: ActionResult = ActionResult.Success
    private val current = MutableStateFlow(snapshot)

    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
    override val screenChanges: Flow<ScreenSnapshot> = current.filterNotNull()
    override suspend fun capture(): ScreenSnapshot? = snapshot
    override suspend fun perform(action: ScreenAction): ActionResult {
        performed += action
        return nextResult
    }
    override suspend fun screenshot(): Screenshot? = null
}
