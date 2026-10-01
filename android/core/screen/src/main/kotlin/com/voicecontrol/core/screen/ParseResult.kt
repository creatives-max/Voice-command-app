package com.voicecontrol.core.screen

import com.voicecontrol.core.model.ScreenSnapshot

/** A snapshot plus the live nodes behind each element id, so actions can target the exact node. */
class ParseResult<N : UiNode>(
    val snapshot: ScreenSnapshot,
    val nodesById: Map<String, N>,
)
