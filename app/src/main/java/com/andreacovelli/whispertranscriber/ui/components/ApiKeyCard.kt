package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.ApiKeyState
import com.andreacovelli.whispertranscriber.KeyValidationState
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
    forgetMessage: String,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var inputKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var revealedSavedKey by remember { mutableStateOf<String?>(null) }
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
                label = { Text(if (apiKeyState is ApiKeyState.Present) "Replace saved key (optional)" else label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && inputKey.isNotBlank()) onSave(inputKey)
                    },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
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
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide API key" else "Show API key",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            when (keyValidationState) {
                is KeyValidationState.Validating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Validating API key...", style = MaterialTheme.typography.labelSmall)
                    }
                }
                is KeyValidationState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            keyValidationState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is KeyValidationState.Idle -> {
                    if (apiKeyState is ApiKeyState.Present) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Encrypted & saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (apiKeyState is ApiKeyState.Error) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "Keystore Error: ${apiKeyState.message}. Please re-enter your key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    onForget()
                    inputKey = ""
                    revealedSavedKey = null
                    showKey = false
                    scope.launch { snackbarHostState.showSnackbar(forgetMessage) }
                }) {
                    Text("Forget key")
                }
            }
        }
    }
}
