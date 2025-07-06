package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreacovelli.whispertranscriber.ui.theme.WhisperTranscriberTheme // Change to your theme package

private fun maskApiKey(apiKey: String): String {
    if (apiKey.length <= 8) {
        return "****" // Return simple asterisks if the key is too short
    }
    val firstChars = apiKey.take(4)
    val lastChars = apiKey.takeLast(4)
    return "$firstChars...$lastChars"
}
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the incoming share intent
        if (intent?.action == Intent.ACTION_SEND) {
            intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                viewModel.handleAudioShare(uri)
            }
        }

        setContent {
            WhisperTranscriberTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isApiKeyFocused by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Audio Transcriber") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Google Cloud API Key", style = MaterialTheme.typography.titleMedium)

            // The TextField is now directly bound to the ViewModel state
            OutlinedTextField(
                // 2. Conditionally display the masked or real key
                value = if (isApiKeyFocused || uiState.apiKey.isEmpty()) {
                    uiState.apiKey // Show real key if focused or empty
                } else {
                    maskApiKey(uiState.apiKey) // Show masked key if not focused
                },
                onValueChange = { newText ->
                    // This always provides the real, full text to the ViewModel
                    viewModel.onApiKeyChange(newText)
                },
                label = { Text("Paste your API key here") },
                modifier = Modifier
                    .fillMaxWidth()
                    // 3. Update the focus state when the user clicks in or out
                    .onFocusChanged { focusState ->
                        isApiKeyFocused = focusState.isFocused
                    }
            )

            Button(
                onClick = { viewModel.saveApiKey() }, // The onClick is now simpler
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Key")
            }

            HorizontalDivider()

            // Status and Result Section
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(uiState.status)

            if (uiState.transcription.isNotEmpty()) {
                Text("Transcription", style = MaterialTheme.typography.titleMedium)
                Text(uiState.transcription)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    // We wrap our screen in the app's theme to see it correctly.
    WhisperTranscriberTheme {
        // Since the preview can't use a real ViewModel, we create a
        // fake one on the fly. We'll pretend the user has already
        // shared a file and we have a result.
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Key Input Section
            Text("OpenAI API Key", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = "sk-...",
                onValueChange = { }, // No action in preview
                label = { Text("Paste your API key here") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                Text("Save Key")
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            // Status and Result Section
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text("Success!") // Hardcoded status for preview

            Text("Transcription", style = MaterialTheme.typography.titleMedium)
            Text("This is an example of a transcribed audio file. The actual transcription from Whisper will appear here.") // Hardcoded result
        }
    }
    }