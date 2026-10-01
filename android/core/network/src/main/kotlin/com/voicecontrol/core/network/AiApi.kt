package com.voicecontrol.core.network

import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.network.dto.InterpretRequestDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiApi @Inject constructor(private val api: ApiClient) {
    suspend fun interpret(request: InterpretRequestDto): Interpretation =
        api.post<InterpretRequestDto, Interpretation>("/v1/ai/interpret", request)
}
