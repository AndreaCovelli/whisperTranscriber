package com.andreacovelli.whispertranscriber

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class PostTranscriptionWorkSpec(
    val summarizeAfterTranscription: Boolean,
    val notifyResult: Boolean,
    val summarizationSettings: SummarizationSettings,
) {
    fun toInputData(jobId: Int, speakerLabelPrefix: String): Data = workDataOf(
        KEY_JOB_ID to jobId,
        TranscriptionWorker.SPEAKER_LABEL_PREFIX_INPUT to speakerLabelPrefix,
        KEY_BACKGROUND_SHARE to true,
        KEY_SUMMARIZE_AFTER_TRANSCRIPTION to summarizeAfterTranscription,
        KEY_NOTIFY_RESULT to notifyResult,
        KEY_SUMMARIZER_PROVIDER to summarizationSettings.provider.name,
        KEY_SUMMARIZER_CUSTOM_MODEL_NAME to summarizationSettings.customModelName,
    )

    companion object {
        private const val KEY_JOB_ID = "jobId"
        private const val KEY_BACKGROUND_SHARE = "backgroundShareEnabled"
        private const val KEY_SUMMARIZE_AFTER_TRANSCRIPTION = "backgroundShareSummarizeAfterTranscription"
        private const val KEY_NOTIFY_RESULT = "backgroundShareNotifyResult"
        private const val KEY_SUMMARIZER_PROVIDER = "backgroundShareSummarizerProvider"
        private const val KEY_SUMMARIZER_CUSTOM_MODEL_NAME = "backgroundShareSummarizerCustomModelName"

        fun fromInputData(inputData: Data): PostTranscriptionWorkSpec? {
            if (!inputData.getBoolean(KEY_BACKGROUND_SHARE, false)) return null
            val provider = inputData.getString(KEY_SUMMARIZER_PROVIDER)
                ?.let { runCatching { TranscriptionProvider.valueOf(it) }.getOrNull() }
                ?: TranscriptionProvider.GEMINI_FLASH
            return PostTranscriptionWorkSpec(
                summarizeAfterTranscription = inputData.getBoolean(KEY_SUMMARIZE_AFTER_TRANSCRIPTION, false),
                notifyResult = inputData.getBoolean(KEY_NOTIFY_RESULT, false),
                summarizationSettings = SummarizationSettings(
                    provider = provider,
                    customModelName = inputData.getString(KEY_SUMMARIZER_CUSTOM_MODEL_NAME).orEmpty(),
                ),
            )
        }
    }
}

@Singleton
class BackgroundShareNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        private const val RESULT_CHANNEL_ID = "background_share_results"
        private const val RESULT_CHANNEL_NAME = "Shared audio results"
        private const val RESULT_GROUP_KEY = "background_share_result_group"
        private const val RESULT_NOTIFICATION_OFFSET = 20_000
        private const val GENERIC_FAILURE_NOTIFICATION_OFFSET = 40_000
        private const val RETRY_NOTIFICATION_OFFSET = 60_000
        private const val PROCESSING_NOTIFICATION_OFFSET = 80_000
        private const val PREPARING_SHARE_NOTIFICATION_ID = 100_000
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureResultChannel()
        ensureTranscriptionChannel()
    }

    fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun showSummary(jobId: Int, displayName: String, summary: String) {
        showResult(
            jobId = jobId,
            title = context.getString(R.string.background_share_summary_ready_title, displayName),
            text = summary,
        )
    }

    fun showTranscription(jobId: Int, displayName: String, transcript: String) {
        showResult(
            jobId = jobId,
            title = context.getString(R.string.background_share_transcription_ready_title, displayName),
            text = transcript,
        )
    }

    fun showFailure(jobId: Int? = null, displayName: String, message: String) {
        notify(
            id = jobId?.let { RESULT_NOTIFICATION_OFFSET + it } ?: genericFailureNotificationId(),
            builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.background_share_summary_failed_title, displayName))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createOpenAppPendingIntent())
                .setAutoCancel(true)
                .setGroup(RESULT_GROUP_KEY),
        )
    }

    fun showRetryScheduled(jobId: Int, displayName: String, message: String) {
        notify(
            id = retryNotificationId(jobId),
            builder = NotificationCompat.Builder(context, TranscriptionWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.transcription_retry_notification_title, displayName))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createOpenAppPendingIntent())
                .setOngoing(true)
                .setAutoCancel(false),
        )
    }

    fun showBackgroundProcessing(jobId: Int, displayName: String, message: String) {
        notify(
            id = processingNotificationId(jobId),
            builder = NotificationCompat.Builder(context, TranscriptionWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.transcription_background_notification_title, displayName))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createOpenAppPendingIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false),
        )
    }

    fun showPreparingShare() {
        val displayName = context.getString(R.string.background_share_generic_audio_label)
        val message = context.getString(R.string.background_share_preparing_notification_body)
        notify(
            id = PREPARING_SHARE_NOTIFICATION_ID,
            builder = NotificationCompat.Builder(context, TranscriptionWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.transcription_background_notification_title, displayName))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createOpenAppPendingIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false),
        )
    }

    fun cancelRetryScheduled(jobId: Int) {
        NotificationManagerCompat.from(context).cancel(retryNotificationId(jobId))
    }

    fun cancelBackgroundProcessing(jobId: Int) {
        NotificationManagerCompat.from(context).cancel(processingNotificationId(jobId))
    }

    fun cancelPreparingShare() {
        NotificationManagerCompat.from(context).cancel(PREPARING_SHARE_NOTIFICATION_ID)
    }

    private fun showResult(jobId: Int, title: String, text: String) {
        notify(
            id = RESULT_NOTIFICATION_OFFSET + jobId,
            builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(createOpenAppPendingIntent())
                .setAutoCancel(true)
                .setGroup(RESULT_GROUP_KEY),
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun ensureResultChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                RESULT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun ensureTranscriptionChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TranscriptionWorker.CHANNEL_ID,
                "Transcription Service",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun genericFailureNotificationId(): Int = GENERIC_FAILURE_NOTIFICATION_OFFSET + Random.nextInt(10_000)

    private fun retryNotificationId(jobId: Int): Int = RETRY_NOTIFICATION_OFFSET + jobId

    private fun processingNotificationId(jobId: Int): Int = PROCESSING_NOTIFICATION_OFFSET + jobId
}
