package com.voicecontrol.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects

@Composable
fun ProfileRoute(onBack: () -> Unit, onSignedOut: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is ProfileEffect.Message -> snackbar.showSnackbar(effect.text)
            ProfileEffect.SignedOut -> onSignedOut()
        }
    }
    ProfileScreen(state, snackbar, onBack, viewModel::dispatch)
}

private data class FieldSpec(val field: ProfileField, val label: String, val keyboard: KeyboardType)

private val fields = listOf(
    FieldSpec(ProfileField.FULL_NAME, "Full name", KeyboardType.Text),
    FieldSpec(ProfileField.EMAIL, "Email", KeyboardType.Email),
    FieldSpec(ProfileField.PHONE, "Mobile number", KeyboardType.Phone),
    FieldSpec(ProfileField.ADDRESS, "Address", KeyboardType.Text),
    FieldSpec(ProfileField.CITY, "City", KeyboardType.Text),
    FieldSpec(ProfileField.STATE, "State", KeyboardType.Text),
    FieldSpec(ProfileField.PINCODE, "PIN code", KeyboardType.Number),
    FieldSpec(ProfileField.DOB, "Date of birth (DD/MM/YYYY)", KeyboardType.Number),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: ProfileState, snackbar: SnackbarHostState, onBack: () -> Unit, onIntent: (ProfileIntent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "VoiceControl offers these when a form asks for them (\"Say yes to use …\"). Leave blank anything you don't want reused.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.email?.let { Text("Signed in as $it", style = MaterialTheme.typography.labelLarge) }
            fields.forEach { spec ->
                OutlinedTextField(
                    value = state.draft.valueOf(spec.field),
                    onValueChange = { onIntent(ProfileIntent.Edit(spec.field, it)) },
                    label = { Text(spec.label) },
                    singleLine = spec.field != ProfileField.ADDRESS,
                    keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { onIntent(ProfileIntent.Save) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (state.signedIn) {
                OutlinedButton(onClick = { onIntent(ProfileIntent.SignOut) }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                DeleteAccount(state.saving) { password -> onIntent(ProfileIntent.DeleteAccount(password)) }
            }
        }
    }
}

/** GDPR erasure: deletes the account on the server and everything on this phone, after re-entering the password. */
@Composable
private fun DeleteAccount(busy: Boolean, onDelete: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    TextButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Delete my account", color = MaterialTheme.colorScheme.error)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Delete your account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your flows, history, profile and phones are deleted from the server and this phone. Organization flows stay with the organization. This can't be undone.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onDelete(password)
                    password = ""
                }, enabled = password.isNotEmpty()) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
