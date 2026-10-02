package com.voicecontrol.feature.assistant.launch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Asks for contacts and call permission when the assistant first needs them ("call Rahul"), then closes. */
class PhonePermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) request.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
    }
}
