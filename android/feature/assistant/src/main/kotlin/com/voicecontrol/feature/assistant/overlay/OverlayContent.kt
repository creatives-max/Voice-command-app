package com.voicecontrol.feature.assistant.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement

/** Callbacks from the overlay UI to the assistant controller. */
interface OverlayActions {
    fun onMicTap()
    fun onMicLongPress()
    fun onElementTap(element: ScreenElement)
    fun onScrollUp()
    fun onScrollDown()
    fun onBack()
    fun onClosePanel()
    fun onHelpVideo(url: String)
    /** Screen-reader mode: read the whole screen aloud and navigate it by voice. */
    fun onReadScreen()
    /** Revert the last fill, toggle or button press. */
    fun onUndo()
    /** Start "teach by doing": record what the user does by touch on this screen and the next ones. */
    fun onTeach() {}
    /** Fill this screen's fields from a photo of a document (read on the phone). */
    fun onScanDocument() {}
    /** Hide the floating mic (it still shows while VoiceControl is talking; Settings brings it back). */
    fun onHideMic() {}
}

@Composable
fun OverlayContent(state: OverlayUiState, actions: OverlayActions, onDrag: (Float, Float) -> Unit, alignStart: Boolean = false) {
    if (!state.visible) return
    // The mic stays at the bottom corner nearest the screen edge; the caption and panel open toward the middle.
    Column(horizontalAlignment = if (alignStart) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = state.panelOpen) { ElementPanel(state, actions) }
        AnimatedVisibility(visible = !state.panelOpen && (state.caption != null || state.heard != null)) {
            CaptionBubble(state, actions)
        }
        MicBubble(state, actions, onDrag)
    }
}

/** Neon colors of the bubble's ring and glow for each state. */
private fun ringColors(mode: BubbleMode, teaching: Boolean): List<Color> = when {
    teaching -> listOf(Color(0xFFFF3B5C), Color(0xFFFF8A00), Color(0xFFFF3B5C))
    else -> when (mode) {
        BubbleMode.IDLE -> listOf(Color(0xFF22D3EE), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF22D3EE))
        BubbleMode.LISTENING -> listOf(Color(0xFFFF3B5C), Color(0xFFFF8A00), Color(0xFFFFD60A), Color(0xFFFF3B5C))
        BubbleMode.SPEAKING -> listOf(Color(0xFF10B981), Color(0xFF22D3EE), Color(0xFF10B981))
        BubbleMode.THINKING -> listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF22D3EE), Color(0xFF8B5CF6))
        BubbleMode.ACTING -> listOf(Color(0xFFF59E0B), Color(0xFFFFD60A), Color(0xFFF59E0B))
        BubbleMode.ERROR -> listOf(Color(0xFFEF4444), Color(0xFFB91C1C), Color(0xFFEF4444))
    }
}

/**
 * The floating mic: a dark glass core inside a rotating neon ring with a soft glow. While listening,
 * ripples spread with the voice; thinking spins the ring faster; teaching pulses red.
 */
@Composable
private fun MicBubble(state: OverlayUiState, actions: OverlayActions, onDrag: (Float, Float) -> Unit) {
    val icon = when (state.mode) {
        BubbleMode.IDLE, BubbleMode.LISTENING -> Icons.Filled.Mic
        BubbleMode.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
        BubbleMode.THINKING -> Icons.Filled.HourglassTop
        BubbleMode.ACTING -> Icons.Filled.TouchApp
        BubbleMode.ERROR -> Icons.Filled.ErrorOutline
    }
    val colors = ringColors(state.mode, state.teaching)
    val busy = state.mode == BubbleMode.THINKING || state.mode == BubbleMode.ACTING
    val transition = rememberInfiniteTransition(label = "mic")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (busy) 1_100 else 4_000, easing = LinearEasing)),
        label = "spin",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "wave",
    )
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse",
    )
    val listening = state.mode == BubbleMode.LISTENING
    val level = state.micLevel.coerceIn(0f, 1f)
    val scale = when {
        state.teaching -> pulse
        listening -> 1f + level * 0.08f
        else -> 1f
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(84.dp)
            .scale(scale)
            .semantics {
                contentDescription = when {
                    state.teaching -> "Stop recording (${state.taughtSteps} steps)"
                    state.sessionActive -> "Stop VoiceControl"
                    else -> "Start VoiceControl"
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { actions.onMicTap() }, onLongPress = { actions.onMicLongPress() })
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val core = size.minDimension * 0.33f
            // Soft glow.
            drawCircle(
                Brush.radialGradient(listOf(colors[0].copy(alpha = 0.45f), Color.Transparent), center, size.minDimension / 2),
                radius = size.minDimension / 2,
                center = center,
            )
            // Voice ripples.
            if (listening || state.teaching) {
                for (i in 0..1) {
                    val t = (wave + i * 0.5f) % 1f
                    drawCircle(
                        colors[0].copy(alpha = (1f - t) * (0.35f + level * 0.4f)),
                        radius = core * (1.15f + t * (0.35f + level * 0.25f)),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            // Rotating neon ring.
            rotate(spin, center) {
                drawCircle(Brush.sweepGradient(colors, center), radius = core * 1.12f, center = center, style = Stroke(width = 3.5.dp.toPx()))
            }
            // Dark glass core with a light edge.
            drawCircle(Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0B1120)), Offset(center.x - core, center.y - core), Offset(center.x + core, center.y + core)), radius = core, center = center)
            drawCircle(Color.White.copy(alpha = 0.18f), radius = core, center = center, style = Stroke(width = 1.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(center.x - core * 0.35f, center.y - core * 0.45f), core * 0.8f),
                radius = core,
                center = center,
            )
        }
        Icon(
            when {
                state.teaching -> Icons.Filled.FiberManualRecord
                state.sessionActive && state.mode == BubbleMode.IDLE -> Icons.Filled.Stop
                else -> icon
            },
            contentDescription = null,
            tint = if (state.teaching) Color(0xFFFF3B5C) else Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun CaptionBubble(state: OverlayUiState, actions: OverlayActions) {
    Card(
        modifier = Modifier.widthIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.progress?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            state.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.heard?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            state.helpVideoUrl?.let { url ->
                Row(
                    Modifier.clickable { actions.onHelpVideo(url) }.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(" Watch help video", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ElementPanel(state: OverlayUiState, actions: OverlayActions) {
    Card(
        modifier = Modifier.widthIn(min = 260.dp, max = 300.dp),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("On this screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = actions::onClosePanel) { Icon(Icons.Filled.Close, "Close") }
            }
            HorizontalDivider()
            if (state.panelElements.isEmpty()) {
                Text("No fields or buttons found.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(state.panelElements, key = { it.id }) { element -> ElementRow(element) { actions.onElementTap(element) } }
            }
            HorizontalDivider()
            // Two rows of four labelled tools, so every one fits and is easy to recognize.
            val tools = listOf(
                Tool(Icons.Filled.KeyboardArrowUp, "Up", "Scroll up", actions::onScrollUp),
                Tool(Icons.Filled.KeyboardArrowDown, "Down", "Scroll down", actions::onScrollDown),
                Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back", "Back", actions::onBack),
                Tool(Icons.AutoMirrored.Filled.Undo, "Undo", "Undo last action", actions::onUndo),
                Tool(Icons.Filled.RecordVoiceOver, "Read", "Read screen aloud", actions::onReadScreen),
                Tool(Icons.Filled.FiberManualRecord, "Teach", "Teach a flow by doing it", actions::onTeach),
                Tool(Icons.Filled.DocumentScanner, "Scan", "Fill from a photo of a document", actions::onScanDocument),
                Tool(Icons.Filled.VisibilityOff, "Hide mic", "Hide the floating mic", actions::onHideMic),
            )
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tools.chunked(TOOLS_PER_ROW).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { tool -> ToolButton(tool, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementRow(element: ScreenElement, onClick: () -> Unit) {
    val icon = when {
        element.isSensitive -> Icons.Filled.Lock
        element.kind == ElementKind.TEXT_FIELD -> Icons.Filled.Edit
        else -> Icons.Filled.TouchApp
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = element.isEnabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Column(Modifier.padding(start = 10.dp)) {
            Text(element.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = element.fieldType?.name?.lowercase() ?: element.kind.name.lowercase()
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class Tool(val icon: ImageVector, val label: String, val description: String, val onClick: () -> Unit)

private const val TOOLS_PER_ROW = 4

@Composable
private fun ToolButton(tool: Tool, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = tool.onClick)
            .semantics { contentDescription = tool.description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(tool.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(
            tool.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
