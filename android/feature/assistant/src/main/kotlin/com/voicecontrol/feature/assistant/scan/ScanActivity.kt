package com.voicecontrol.feature.assistant.scan

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.voicecontrol.core.ui.theme.VoiceControlTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/**
 * "Fill from a photo": take or pick a photo of a document (PAN, Aadhaar, licence, bill…), read it on
 * the phone, review the values, and fill them into the form the user came from. The screen is kept
 * out of screenshots and the photo is deleted as soon as it has been read.
 */
@AndroidEntryPoint
class ScanActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()
    private var photo: File? = null
    private var handedOver = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = photo ?: return@registerForActivityResult
        if (saved) viewModel.read(uriFor(file)) { file.delete() } else file.delete()
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.read(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            VoiceControlTheme {
                ScanScreen(
                    viewModel = viewModel,
                    onTakePhoto = ::takePhoto,
                    onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onFill = {
                        if (viewModel.fill()) {
                            handedOver = true
                            finish()
                        }
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    private fun takePhoto() {
        val dir = File(cacheDir, SCAN_DIR).apply { mkdirs() }
        val file = File(dir, "document-${System.currentTimeMillis()}.jpg")
        photo = file
        runCatching { takePicture.launch(uriFor(file)) }.onFailure { file.delete() }
    }

    private fun uriFor(file: File) = FileProvider.getUriForFile(this, "$packageName.scan", file)

    override fun onDestroy() {
        if (isFinishing) {
            File(cacheDir, SCAN_DIR).deleteRecursively()
            if (!handedOver) viewModel.cancel()
        }
        super.onDestroy()
    }

    private companion object {
        const val SCAN_DIR = "scan"
    }
}
