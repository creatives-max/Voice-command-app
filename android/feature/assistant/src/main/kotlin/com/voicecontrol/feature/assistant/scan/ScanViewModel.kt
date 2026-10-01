package com.voicecontrol.feature.assistant.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.engine.DocumentExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val reader: DocumentTextReader,
    private val session: ScanSession,
) : ViewModel() {

    private val _stage = MutableStateFlow<ScanStage>(
        if (session.target == null) ScanStage.Problem(NO_FORM) else ScanStage.Choose,
    )
    val stage: StateFlow<ScanStage> = _stage.asStateFlow()

    /** Reads the photo at [uri]; [cleanup] deletes it afterwards (camera photos are temporary files). */
    fun read(uri: Uri, cleanup: () -> Unit = {}) {
        _stage.value = ScanStage.Reading
        viewModelScope.launch {
            val text = runCatching { reader.read(uri) }
            cleanup()
            _stage.value = text.fold(
                onSuccess = { ScanReview.stage(session.target, DocumentExtractor.extract(it)) },
                onFailure = { ScanStage.Problem("Couldn't read this photo. Try another one.") },
            )
        }
    }

    private fun updateItem(index: Int, change: (ReviewItem) -> ReviewItem) = _stage.update { s ->
        if (s !is ScanStage.Review) s else s.copy(items = s.items.mapIndexed { i, item -> if (i == index) change(item) else item })
    }

    fun toggle(index: Int) = updateItem(index) { it.copy(checked = !it.checked) }
    fun edit(index: Int, value: String) = updateItem(index) { it.copy(value = value.take(MAX_VALUE)) }
    fun reveal(index: Int) = updateItem(index) { it.copy(revealed = true) }

    fun retry() {
        _stage.value = if (session.target == null) ScanStage.Problem(NO_FORM) else ScanStage.Choose
    }

    /** Hands the chosen values to [ScanSession]; true when the screen should close to let them be filled. */
    fun fill(): Boolean {
        val review = _stage.value as? ScanStage.Review ?: return false
        val fills = ScanReview.chosen(review.items)
        if (fills.isEmpty()) return false
        session.fillWhenBack(fills)
        return true
    }

    fun cancel() = session.cancel()

    private companion object {
        const val MAX_VALUE = 300
        const val NO_FORM = "Open the form you want to fill, then tap “Fill from a photo” in the VoiceControl panel."
    }
}
