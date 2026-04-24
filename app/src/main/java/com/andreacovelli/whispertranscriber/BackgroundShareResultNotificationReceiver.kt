package com.andreacovelli.whispertranscriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.BackgroundShareResultKind
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BackgroundShareResultNotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PREVIOUS_RESULT_PAGE && intent.action != ACTION_NEXT_RESULT_PAGE) return

        val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
        if (jobId == -1) return

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val resultKind = intent.getStringExtra(EXTRA_RESULT_KIND)
            ?.let { enumValueOfOrNull<BackgroundShareResultKind>(it) }
            ?: return
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0)

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                val job = repository.getJob(jobId)
                val text = when (resultKind) {
                    BackgroundShareResultKind.TRANSCRIPTION -> job?.transcript.orEmpty()
                    BackgroundShareResultKind.SUMMARY -> job?.summary.orEmpty()
                }
                if (text.isBlank()) {
                    backgroundShareNotifier.cancelResult(jobId)
                    return@launch
                }
                backgroundShareNotifier.showResultPage(
                    jobId = jobId,
                    displayName = displayName.ifBlank { job?.displayName.orEmpty() },
                    resultKind = resultKind,
                    text = text,
                    pageIndex = pageIndex,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PREVIOUS_RESULT_PAGE =
            "com.andreacovelli.whispertranscriber.action.PREVIOUS_RESULT_PAGE"
        const val ACTION_NEXT_RESULT_PAGE =
            "com.andreacovelli.whispertranscriber.action.NEXT_RESULT_PAGE"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_RESULT_KIND = "resultKind"
        const val EXTRA_PAGE_INDEX = "pageIndex"
    }
}

private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String): T? =
    enumValues<T>().firstOrNull { entry -> entry.name == value }
