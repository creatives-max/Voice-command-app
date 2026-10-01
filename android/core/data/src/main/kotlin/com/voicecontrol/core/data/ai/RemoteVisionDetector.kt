package com.voicecontrol.core.data.ai

import android.util.Base64
import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.network.AiApi
import com.voicecontrol.core.network.dto.VisionRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a downscaled screenshot to the backend vision model (only when the user enabled the vision
 * fallback and is signed in) and maps detected boxes back to real screen coordinates.
 */
@Singleton
class RemoteVisionDetector @Inject constructor(
    private val ai: AiApi,
    private val tokens: TokenStore,
) : VisionDetector {

    override suspend fun detect(screenshot: Screenshot, packageName: String, language: Language): List<ScreenElement>? {
        if (tokens.tokens() == null) return null
        val response = ai.vision(
            VisionRequestDto(
                packageName = packageName,
                imageBase64 = Base64.encodeToString(screenshot.jpeg, Base64.NO_WRAP),
                width = screenshot.width,
                height = screenshot.height,
                language = language,
            ),
        )
        return response.elements.map { e ->
            val left = screenshot.toScreenX(e.x)
            val top = screenshot.toScreenY(e.y)
            val sensitive = e.fieldType?.isSensitive == true
            ScreenElement(
                id = if (e.id.startsWith(VisionDetector.VISION_ID_PREFIX)) e.id else VisionDetector.VISION_ID_PREFIX + e.id,
                kind = e.kind,
                label = e.label,
                fieldType = if (e.kind == ElementKind.TEXT_FIELD) e.fieldType ?: FieldType.TEXT else null,
                isSensitive = sensitive,
                bounds = Bounds(left, top, screenshot.toScreenX(e.x + e.width), screenshot.toScreenY(e.y + e.height)),
            )
        }
    }
}
