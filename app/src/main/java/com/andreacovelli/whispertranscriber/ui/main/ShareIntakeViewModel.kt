package com.andreacovelli.whispertranscriber.ui.main

import android.content.Context
import android.content.Intent
import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.SharedAudioIntentHandler
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.userMessageOr
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface ShareIntakeState {
    data object Preparing : ShareIntakeState
    data class ForwardToMain(val intent: Intent) : ShareIntakeState
    data object Finished : ShareIntakeState
}

@HiltViewModel
class ShareIntakeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApiKeyManager,
    private val queueService: TranscriptionQueueService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareIntakeState>(ShareIntakeState.Preparing)
    internal val state = _state.asStateFlow()
    private var started = false

    fun accept(incomingIntent: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                handleSharedAudio(Intent(incomingIntent))
            } finally {
                if (_state.value == ShareIntakeState.Preparing) _state.value = ShareIntakeState.Finished
            }
        }
    }

    private suspend fun handleSharedAudio(incomingIntent: Intent) {
        if ((incomingIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        val sharedUris = SharedAudioIntentHandler.extractSharedUris(incomingIntent)
        if (sharedUris.isEmpty()) return

        val backgroundShareEnabled = settings.backgroundShareSummaryNotificationsEnabledFlow.first()
        if (!(backgroundShareEnabled && backgroundShareNotifier.canPostNotifications())) {
            _state.value = ShareIntakeState.ForwardToMain(incomingIntent)
            return
        }

        backgroundShareNotifier.showPreparingShare()
        var jobsCommitted = false
        try {
            val queueSettings = settings.getTranscriptionQueueSettings()
            val automaticSummarizationEnabled = settings.automaticSummarizationFlow.first()
            val summarizationSettings = settings.getSummarizationSettings()
            queueService.enqueueSharedAudio(
                uris = sharedUris,
                provider = queueSettings.provider,
                customModelName = queueSettings.customModelName,
                speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                hints = queueSettings.hints(),
                preferImmediateBackgroundStart = true,
                postTranscriptionWorkSpec = PostTranscriptionWorkSpec(
                    summarizeAfterTranscription = automaticSummarizationEnabled,
                    notifyResult = true,
                    summarizationSettings = summarizationSettings,
                ),
                onJobsCommitted = { jobsCommitted = true },
            )
        } catch (e: CancellationException) {
            // The share receiver was closed (for example with Back) before the audio was queued.
            if (!jobsCommitted) {
                showBackgroundShareQueueFailure(UserMessage(R.string.background_share_interrupted_body))
            }
            throw e
        } catch (e: IOException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: IllegalArgumentException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: IllegalStateException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: SecurityException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: SQLException) {
            showBackgroundShareQueueFailure(e)
        } finally {
            backgroundShareNotifier.cancelPreparingShare()
        }
    }

    private fun showBackgroundShareQueueFailure(error: Exception) {
        showBackgroundShareQueueFailure(error.userMessageOr(R.string.background_share_queue_failed_body))
    }

    private fun showBackgroundShareQueueFailure(message: UserMessage) {
        backgroundShareNotifier.showFailure(
            displayName = context.getString(R.string.background_share_generic_audio_label),
            message = message,
        )
    }
}
