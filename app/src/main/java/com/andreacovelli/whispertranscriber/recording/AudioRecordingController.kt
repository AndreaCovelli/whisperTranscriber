package com.andreacovelli.whispertranscriber.recording

import android.content.Context
import android.database.SQLException
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andreacovelli.whispertranscriber.AudioRecordingService
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Owns the single in-app microphone recording. [AudioRecordingService] keeps the process in the
 * foreground while it runs, so recording continues with the screen off or another app open.
 *
 * Call [start], [stop], and [discard] on the main thread; the service and MediaRecorder callbacks
 * run there too.
 */
@Singleton
class AudioRecordingController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: RecordingLibrary,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private class Session(val recorder: MediaRecorder, val file: File, val displayName: String)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // Buffered so a recording stopped from the notification reaches the main screen once it is open again.
    private val _outcomes = Channel<RecordingOutcome>(Channel.BUFFERED)
    val outcomes: Flow<RecordingOutcome> = _outcomes.receiveAsFlow()

    private var session: Session? = null

    fun start() {
        if (_state.value != RecordingState.Idle) return
        _state.value = RecordingState.Starting
        try {
            ContextCompat.startForegroundService(context, AudioRecordingService.startIntent(context))
        } catch (_: IllegalStateException) {
            abortStart()
        } catch (_: SecurityException) {
            abortStart()
        }
    }

    /** Finishes the recording, saves it to shared storage, and reports it through [outcomes]. */
    fun stop() {
        when (_state.value) {
            RecordingState.Starting -> _state.value = RecordingState.Idle
            is RecordingState.Recording -> finish(keep = true)
            RecordingState.Idle, RecordingState.Saving -> Unit
        }
    }

    /** Stops the recording and deletes it without saving. */
    fun discard() {
        when (_state.value) {
            RecordingState.Starting -> _state.value = RecordingState.Idle
            is RecordingState.Recording -> finish(keep = false)
            RecordingState.Idle, RecordingState.Saving -> Unit
        }
    }

    /** Called by [AudioRecordingService] once it runs in the foreground. */
    internal fun openMicrophone(): Boolean {
        if (_state.value != RecordingState.Starting) return false
        val startedAtMillis = System.currentTimeMillis()
        val directory = File(context.noBackupFilesDir, RECORDING_DIR).apply { mkdirs() }
        // Only one recording runs at a time, so anything left here was cut off when the process died.
        directory.listFiles()?.forEach(File::delete)
        val file = File(directory, "$RECORDING_FILE_PREFIX$startedAtMillis.$RECORDING_FILE_EXTENSION")
        val recorder = newMediaRecorder()
        try {
            recorder.configure(file)
            recorder.prepare()
            recorder.start()
        } catch (_: IOException) {
            return failToOpen(recorder, file)
        } catch (_: RuntimeException) {
            // MediaRecorder reports a busy or unavailable microphone as a bare RuntimeException.
            return failToOpen(recorder, file)
        }
        session = Session(
            recorder = recorder,
            file = file,
            displayName = recordingFileName(context.getString(R.string.recording_file_label), startedAtMillis),
        )
        _state.value = RecordingState.Recording(SystemClock.elapsedRealtime())
        return true
    }

    /** Called by [AudioRecordingService] when it cannot enter the foreground. */
    internal fun abortStart() {
        if (_state.value != RecordingState.Starting) return
        _state.value = RecordingState.Idle
        _outcomes.trySend(RecordingOutcome.Failed(UserMessage(R.string.recording_start_failed)))
    }

    private fun failToOpen(recorder: MediaRecorder, file: File): Boolean {
        recorder.release()
        file.delete()
        abortStart()
        return false
    }

    private fun newMediaRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }

    private fun MediaRecorder.configure(file: File) {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(RECORDING_SAMPLE_RATE_HZ)
        setAudioEncodingBitRate(RECORDING_BIT_RATE)
        setMaxFileSize(MAX_RECORDING_BYTES)
        setOutputFile(file.absolutePath)
        // Reaching the size cap keeps what was recorded; transcribing it is better than losing it.
        setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop()
        }
        setOnErrorListener { _, _, _ -> stop() }
    }

    private fun finish(keep: Boolean) {
        val current = session ?: return
        session = null
        _state.value = RecordingState.Saving
        applicationScope.launch {
            try {
                val stopped = stopRecorder(current.recorder)
                val outcome = when {
                    !stopped -> RecordingOutcome.Failed(UserMessage(R.string.recording_finish_failed))
                    keep -> save(current)
                    else -> null
                }
                outcome?.let { _outcomes.send(it) }
            } finally {
                current.recorder.release()
                current.file.delete()
                _state.value = RecordingState.Idle
            }
        }
    }

    // stop() throws when no audio was captured, for example right after the recording began.
    private fun stopRecorder(recorder: MediaRecorder): Boolean = try {
        recorder.stop()
        true
    } catch (_: RuntimeException) {
        false
    }

    private suspend fun save(session: Session): RecordingOutcome = try {
        val uri = library.publish(session.file, session.displayName)
        RecordingOutcome.Saved(uri, library.savedLocationMessage)
    } catch (_: IOException) {
        saveFailed()
    } catch (_: SecurityException) {
        saveFailed()
    } catch (_: SQLException) {
        saveFailed()
    } catch (_: IllegalArgumentException) {
        saveFailed()
    } catch (_: IllegalStateException) {
        saveFailed()
    }

    private fun saveFailed(): RecordingOutcome = RecordingOutcome.Failed(UserMessage(R.string.recording_save_failed))

    private companion object {
        const val RECORDING_DIR = "recordings"
        const val RECORDING_FILE_PREFIX = "recording_"
    }
}
