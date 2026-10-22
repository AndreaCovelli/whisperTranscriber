package com.andreacovelli.whispertranscriber.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.recording.RecordingState
import java.util.Locale
import kotlinx.coroutines.delay

/** Floating actions of the main screen: record or pick audio, or control the running recording. */
@Composable
fun MainActionButtons(
    recording: RecordingState,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onPickAudio: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (recording) {
            RecordingState.Idle -> {
                if (canRecord) {
                    ExtendedFloatingActionButton(
                        onClick = onRecord,
                        icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
                        text = { Text(stringResource(R.string.main_record_audio)) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = onPickAudio,
                    icon = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
                    text = { Text(stringResource(R.string.main_pick_audio)) },
                )
            }
            RecordingState.Starting, is RecordingState.Recording -> {
                val startedAt = (recording as? RecordingState.Recording)?.startedAtElapsedMillis
                val stopDescription = stringResource(R.string.main_stop_recording_description)
                SmallFloatingActionButton(onClick = onDiscardRecording) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.main_discard_recording),
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = onStopRecording,
                    modifier = Modifier.semantics { contentDescription = stopDescription },
                    icon = { Icon(painterResource(R.drawable.ic_stop), contentDescription = null) },
                    text = { Text(stringResource(R.string.main_stop_recording, rememberElapsedText(startedAt))) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            RecordingState.Saving -> {
                ExtendedFloatingActionButton(
                    onClick = {},
                    icon = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
                    text = { Text(stringResource(R.string.main_saving_recording)) },
                )
            }
        }
    }
}

@Composable
private fun rememberElapsedText(startedAtElapsedMillis: Long?): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsedMillis) {
        if (startedAtElapsedMillis == null) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            // Wake on the next whole second so the timer advances evenly.
            delay(MILLIS_PER_SECOND - (now - startedAtElapsedMillis) % MILLIS_PER_SECOND)
        }
    }
    return formatRecordingElapsed(startedAtElapsedMillis?.let { now - it } ?: 0L)
}

/** "mm:ss", or "h:mm:ss" from the first hour on. */
internal fun formatRecordingElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
