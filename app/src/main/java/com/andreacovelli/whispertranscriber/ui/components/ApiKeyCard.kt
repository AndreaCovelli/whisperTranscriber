package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.ui.settings.KeyValidationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ApiKeyCard(
    title: String,
    label: String,
    apiKeyState: ApiKeyState,
    keyValidationState: KeyValidationState,
    onLoadSavedKey: suspend () -> String?,
    onSave: (String) -> Unit,
    onForget: () -> Unit,
    onResetValidation: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var inputKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var revealedSavedKey by remember { mutableStateOf<String?>(null) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun clearRevealedKey() {
        revealJob?.cancel()
        revealJob = null
        revealedSavedKey = null
        showKey = false
    }
    LaunchedEffect(apiKeyState) {
        if (apiKeyState !is ApiKeyState.Present) clearRevealedKey()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                clearRevealedKey()
                inputKey = ""
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clearRevealedKey()
        }
    }
    val fieldValue = inputKey.ifBlank { revealedSavedKey.orEmpty() }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    inputKey = it
                    if (revealedSavedKey != null) {
                        revealedSavedKey = null
                    }
                    onResetValidation()
                },
                label = {
                    Text(
                        if (apiKeyState is ApiKeyState.Present) {
                            stringResource(
                                R.string.api_key_replace_saved_optional,
                            )
                        } else {
                            label
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && inputKey.isNotBlank()) onSave(inputKey)
                    },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = {
                        revealJob?.cancel()
                        revealJob = scope.launch {
                            if (showKey) {
                                showKey = false
                                if (inputKey.isBlank()) {
                                    revealedSavedKey = null
                                }
                            } else {
                                if (inputKey.isBlank() && apiKeyState is ApiKeyState.Present) {
                                    revealedSavedKey = onLoadSavedKey()
                                }
                                showKey = true
                            }
                        }
                    }) {
                        Icon(
                            painter = painterResource(
                                if (showKey) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                            ),
                            contentDescription = if (showKey) {
                                stringResource(
                                    R.string.api_key_hide,
                                )
                            } else {
                                stringResource(R.string.api_key_show)
                            },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            ApiKeyStatus(keyValidationState = keyValidationState, apiKeyState = apiKeyState)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    onForget()
                    inputKey = ""
                    revealedSavedKey = null
                    showKey = false
                    clearRevealedKey()
                }) {
                    Text(stringResource(R.string.api_key_forget))
                }
            }
        }
    }
}

@Composable
private fun ApiKeyStatus(keyValidationState: KeyValidationState, apiKeyState: ApiKeyState) {
    when (keyValidationState) {
        is KeyValidationState.Validating -> ApiKeyValidatingState()
        is KeyValidationState.Error -> ApiKeyErrorState(message = keyValidationState.message)
        is KeyValidationState.Idle -> ApiKeyIdleState(apiKeyState = apiKeyState)
    }
}

@Composable
private fun ApiKeyValidatingState() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.api_key_validating), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ApiKeyErrorState(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ApiKeyIdleState(apiKeyState: ApiKeyState) {
    when (apiKeyState) {
        is ApiKeyState.Present -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.api_key_encrypted_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ApiKeyState.Error -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.api_key_keystore_error, apiKeyState.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        else -> Unit
    }
}
