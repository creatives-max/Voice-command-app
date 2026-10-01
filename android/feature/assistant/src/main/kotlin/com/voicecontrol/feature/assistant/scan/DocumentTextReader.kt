package com.voicecontrol.feature.assistant.scan

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.voicecontrol.core.engine.OcrBox
import com.voicecontrol.core.engine.OcrLayout
import com.voicecontrol.core.engine.OcrText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the text of a photo. */
fun interface DocumentTextReader {
    suspend fun read(uri: Uri): OcrText
}

/**
 * On-device text recognition with ML Kit's bundled models (Devanagari, which also reads English,
 * plus Latin). The photo never leaves the phone.
 */
@Singleton
class MlKitDocumentTextReader @Inject constructor(@ApplicationContext private val context: Context) : DocumentTextReader {

    override suspend fun read(uri: Uri): OcrText = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            OcrLayout.merge(boxes(devanagari.process(image).await()), boxes(latin.process(image).await()))
        } finally {
            devanagari.close()
            latin.close()
        }
    }

    private fun boxes(text: Text): List<OcrBox> = text.textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrBox(line.text, box.left, box.top, box.right, box.bottom)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
