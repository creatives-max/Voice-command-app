package com.voicecontrol.core.network

import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.network.dto.InterpretRequestDto
import com.voicecontrol.core.network.dto.VisionRequestDto
import com.voicecontrol.core.network.dto.VisionResponseDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiApi @Inject constructor(private val api: ApiClient) {
    suspend fun interpret(request: InterpretRequestDto): Interpretation =
        api.post<InterpretRequestDto, Interpretation>("/v1/ai/interpret", request)

    suspend fun questions(request: com.voicecontrol.core.network.dto.QuestionsRequestDto): com.voicecontrol.core.network.dto.QuestionsResponseDto =
        api.post<com.voicecontrol.core.network.dto.QuestionsRequestDto, com.voicecontrol.core.network.dto.QuestionsResponseDto>("/v1/ai/questions", request)

    suspend fun agentStep(request: com.voicecontrol.core.network.dto.AgentStepRequestDto): com.voicecontrol.core.network.dto.AgentStepDto =
        api.post<com.voicecontrol.core.network.dto.AgentStepRequestDto, com.voicecontrol.core.network.dto.AgentStepDto>("/v1/ai/agent/step", request)

    suspend fun vision(request: VisionRequestDto): VisionResponseDto =
        api.post<VisionRequestDto, VisionResponseDto>("/v1/ai/vision", request)
}
