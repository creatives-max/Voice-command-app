package com.voicecontrol.core.network.dto

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class ScreenElementDto(
    val id: String,
    val kind: ElementKind,
    val label: String,
    val fieldType: FieldType? = null,
    val hint: String? = null,
    val value: String? = null,
    val isSensitive: Boolean = false,
    val isEnabled: Boolean = true,
    val isChecked: Boolean? = null,
)

@Serializable
data class ScreenContextDto(
    val packageName: String,
    val activityName: String? = null,
    val title: String? = null,
    val elements: List<ScreenElementDto>,
    val signature: String = "",
) {
    companion object {
        /** Only what the backend needs; values of sensitive fields are never included. */
        fun from(snapshot: ScreenSnapshot) = ScreenContextDto(
            packageName = snapshot.packageName,
            activityName = snapshot.activityName,
            title = snapshot.title,
            signature = snapshot.signature,
            elements = snapshot.elements.map { e ->
                ScreenElementDto(
                    id = e.id,
                    kind = e.kind,
                    label = e.label,
                    fieldType = e.fieldType,
                    hint = e.hint,
                    value = if (e.isSensitive) null else e.value,
                    isSensitive = e.isSensitive,
                    isEnabled = e.isEnabled,
                    isChecked = e.isChecked,
                )
            },
        )
    }
}

@Serializable
data class InterpretRequestDto(
    val screen: ScreenContextDto,
    val currentFieldId: String?,
    val utterance: String,
    val language: Language,
    val question: String?,
    val transliterate: Boolean,
    /** Earlier answers in this session (non-sensitive), so the model can resolve "same as above", "his", "that one". */
    val memory: List<MemoryDto> = emptyList(),
)

@Serializable
data class MemoryDto(val label: String, val value: String)

@Serializable
data class ApiErrorDto(val error: String = "error", val message: String = "Request failed")

@Serializable
data class VisionRequestDto(val packageName: String, val imageBase64: String, val width: Int, val height: Int, val language: Language)

@Serializable
data class VisionElementDto(
    val id: String,
    val kind: ElementKind,
    val label: String,
    val fieldType: FieldType? = null,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class VisionResponseDto(val elements: List<VisionElementDto>, val source: String = "")
