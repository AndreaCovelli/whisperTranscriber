package com.andreacovelli.whispertranscriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.BackgroundShareResultKind
import com.andreacovelli.whispertranscriber.background.StoredResultPublisher
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BackgroundShareResultNotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storedResultPublisher: StoredResultPublisher

    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
        if (jobId == -1) return

        if (intent.action == ACTION_DISMISS_RESULT_PAGE) {
            backgroundShareNotifier.clearCache(jobId)
            return
        }

        if (intent.action != ACTION_PREVIOUS_RESULT_PAGE && intent.action != ACTION_NEXT_RESULT_PAGE) return

        val resultKind = intent.getStringExtra(EXTRA_RESULT_KIND)
            ?.let { enumValueOfOrNull<BackgroundShareResultKind>(it) }
            ?: return
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0)

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                storedResultPublisher.publish(jobId, resultKind, pageIndex)
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
        const val ACTION_DISMISS_RESULT_PAGE =
            "com.andreacovelli.whispertranscriber.action.DISMISS_RESULT_PAGE"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_RESULT_KIND = "resultKind"
        const val EXTRA_PAGE_INDEX = "pageIndex"
    }
}

private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String): T? =
    enumValues<T>().firstOrNull { entry -> entry.name == value }
