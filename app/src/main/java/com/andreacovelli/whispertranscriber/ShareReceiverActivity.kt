package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.database.SQLException
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
            try {
                handleSharedAudio()
            } finally {
                finish()
            }
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

        backgroundShareNotifier.showPreparingShare()
        try {
            val queueSettings = settings.getTranscriptionQueueSettings()
            val automaticSummarizationEnabled = settings.automaticSummarizationFlow.first()
            val summarizationSettings = settings.getSummarizationSettings()
            queueService.enqueueSharedAudio(
                uris = sharedUris,
                provider = queueSettings.provider,
                customModelName = queueSettings.customModelName,
                speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                preferImmediateBackgroundStart = true,
                postTranscriptionWorkSpec = PostTranscriptionWorkSpec(
                    summarizeAfterTranscription = automaticSummarizationEnabled,
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
        } catch (e: SQLException) {
            showBackgroundShareQueueFailure(e)
        } finally {
            backgroundShareNotifier.cancelPreparingShare()
        }
    }

    private fun showBackgroundShareQueueFailure(exception: Exception) {
        backgroundShareNotifier.showFailure(
            displayName = getString(R.string.background_share_generic_audio_label),
            message = exception.message ?: getString(R.string.background_share_queue_failed_body),
        )
    }
}
