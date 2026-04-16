package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject
    lateinit var settings: ApiKeyManager

    @Inject
    lateinit var queueService: TranscriptionQueueService

    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            handleSharedAudio()
            finish()
        }
    }

    private suspend fun handleSharedAudio() {
        val incomingIntent = intent ?: return
        if ((incomingIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        val sharedUris = SharedAudioIntentHandler.extractSharedUris(incomingIntent)
        if (sharedUris.isEmpty()) return

        val backgroundShareEnabled = settings.backgroundShareSummaryNotificationsEnabledFlow.first()
        if (!(backgroundShareEnabled && backgroundShareNotifier.canPostNotifications())) {
            startActivity(
                Intent(incomingIntent).apply {
                    setClass(this@ShareReceiverActivity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            return
        }

        try {
            // Share URI grants are tied to this activity lifecycle, so stage the inputs before finish().
            val preparedAudio = queueService.prepareSharedAudio(sharedUris)
            val queueSettings = settings.getTranscriptionQueueSettings()
            val summarizationSettings = settings.getSummarizationSettings()
            queueService.enqueuePreparedSharedAudio(
                preparedAudio = preparedAudio,
                provider = queueSettings.provider,
                customModelName = queueSettings.customModelName,
                speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                backgroundShareWorkSpec = BackgroundShareWorkSpec(
                    summarizeAfterTranscription = true,
                    notifyResult = true,
                    summarizationSettings = summarizationSettings,
                ),
            )
        } catch (e: IOException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: IllegalArgumentException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: IllegalStateException) {
            showBackgroundShareQueueFailure(e)
        } catch (e: SecurityException) {
            showBackgroundShareQueueFailure(e)
        }
    }

    private fun showBackgroundShareQueueFailure(exception: Exception) {
        backgroundShareNotifier.showFailure(
            displayName = getString(R.string.background_share_generic_audio_label),
            message = exception.message ?: getString(R.string.background_share_queue_failed_body),
        )
    }
}
