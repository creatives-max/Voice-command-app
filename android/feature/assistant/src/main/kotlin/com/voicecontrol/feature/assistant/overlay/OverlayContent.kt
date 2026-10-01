package com.voicecontrol.feature.assistant.overlay

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
}

@Composable
fun OverlayContent(state: OverlayUiState, actions: OverlayActions, onDrag: (Float, Float) -> Unit) {
    if (!state.visible) return
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = state.panelOpen) { ElementPanel(state, actions) }
        AnimatedVisibility(visible = !state.panelOpen && (state.caption != null || state.heard != null)) {
            CaptionBubble(state, actions)
        }
        MicBubble(state, actions, onDrag)
    }
}

@Composable
private fun MicBubble(state: OverlayUiState, actions: OverlayActions, onDrag: (Float, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, icon) = when (state.mode) {
        BubbleMode.IDLE -> colors.primary to Icons.Filled.Mic
        BubbleMode.LISTENING -> Color(0xFFDC2626) to Icons.Filled.Mic
        BubbleMode.SPEAKING -> colors.tertiary to Icons.AutoMirrored.Filled.VolumeUp
        BubbleMode.THINKING -> colors.secondary to Icons.Filled.HourglassTop
        BubbleMode.ACTING -> colors.secondary to Icons.Filled.TouchApp
        BubbleMode.ERROR -> colors.error to Icons.Filled.ErrorOutline
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "scale",
    )
    val scale = when {
        state.teaching -> pulse
        state.mode == BubbleMode.LISTENING -> pulse + state.micLevel.coerceIn(0f, 1f) * 0.1f
        else -> 1f
    }
    Surface(
        shape = CircleShape,
        color = if (state.teaching) Color(0xFFB91C1C) else container,
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(64.dp)
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
        Box(contentAlignment = Alignment.Center) {
            Icon(
                when {
                    state.teaching -> Icons.Filled.FiberManualRecord
                    state.sessionActive && state.mode == BubbleMode.IDLE -> Icons.Filled.Stop
                    else -> icon
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = actions::onScrollUp) { Icon(Icons.Filled.KeyboardArrowUp, "Scroll up") }
                IconButton(onClick = actions::onScrollDown) { Icon(Icons.Filled.KeyboardArrowDown, "Scroll down") }
                IconButton(onClick = actions::onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = actions::onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo last action") }
                IconButton(onClick = actions::onReadScreen) { Icon(Icons.Filled.RecordVoiceOver, "Read screen aloud") }
                IconButton(onClick = actions::onTeach) { Icon(Icons.Filled.FiberManualRecord, "Teach a flow by doing it") }
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
