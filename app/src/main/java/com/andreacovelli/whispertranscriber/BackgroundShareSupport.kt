package com.andreacovelli.whispertranscriber

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import androidx.core.net.toUri

data class PostTranscriptionWorkSpec(
    val summarizeAfterTranscription: Boolean,
    val notifyResult: Boolean,
    val summarizationSettings: SummarizationSettings,
)

internal const val RESULT_TEXT_PAGE_MAX_LENGTH = 500

internal enum class BackgroundShareResultKind {
    TRANSCRIPTION,
    SUMMARY,
}

private val sentenceBoundaryAbbreviations = setOf(
    "dr",
    "etc",
    "e.g",
    "i.e",
    "jr",
    "mr",
    "mrs",
    "ms",
    "prof",
    "sr",
    "st",
    "vs",
)

internal data class BackgroundShareResultPage(val text: String, val index: Int, val total: Int) {
    val hasPrevious: Boolean
        get() = index > 0

    val hasNext: Boolean
        get() = index < total - 1
}

internal fun splitNotificationTextPages(text: String, maxChars: Int = RESULT_TEXT_PAGE_MAX_LENGTH): List<String> {
    require(maxChars > 0) { "Page size must be positive." }
    if (text.isEmpty()) return listOf("")

    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val windowEnd = minOf(start + maxChars, text.length)
        if (windowEnd == text.length) {
            pages += text.substring(start)
            break
        }

        val splitEnd = findPreferredPageBreak(text, start, windowEnd) ?: windowEnd
        pages += text.substring(start, splitEnd)
        start = splitEnd
    }

    return pages
}

internal fun resolveNotificationTextPage(
    text: String,
    pageIndex: Int,
    maxChars: Int = RESULT_TEXT_PAGE_MAX_LENGTH,
): BackgroundShareResultPage {
    val pages = splitNotificationTextPages(text, maxChars)
    val resolvedIndex = pageIndex.coerceIn(0, pages.lastIndex)
    return BackgroundShareResultPage(
        text = pages[resolvedIndex],
        index = resolvedIndex,
        total = pages.size,
    )
}

private fun findPreferredPageBreak(text: String, start: Int, windowEnd: Int): Int? {
    findParagraphBreak(text, start, windowEnd)?.let { return it }
    findSentenceBreak(text, start, windowEnd)?.let { return it }
    findLineBreak(text, start, windowEnd)?.let { return it }
    return findWordBreak(text, start, windowEnd)
}

private fun findParagraphBreak(text: String, start: Int, windowEnd: Int): Int? {
    val index = text.lastIndexOf("\n\n", startIndex = windowEnd - 2)
    return if (index >= start) index + 2 else null
}

private fun findSentenceBreak(text: String, start: Int, windowEnd: Int): Int? {
    for (index in (windowEnd - 1) downTo (start + 1)) {
        if (isLikelySentenceBreak(text, index)) {
            return index + 1
        }
    }
    return null
}

private fun findLineBreak(text: String, start: Int, windowEnd: Int): Int? {
    val index = text.lastIndexOf('\n', startIndex = windowEnd - 1)
    return if (index >= start) index + 1 else null
}

private fun findWordBreak(text: String, start: Int, windowEnd: Int): Int? {
    val index = text.lastIndexOf(' ', startIndex = windowEnd - 1)
    return if (index >= start) index + 1 else null
}

internal fun isLikelySentenceBreak(text: String, whitespaceIndex: Int): Boolean {
    if (whitespaceIndex !in 1 until text.length) return false
    if (!text[whitespaceIndex].isWhitespace()) return false

    val punctuationIndex = whitespaceIndex - 1
    val punctuation = text[punctuationIndex]
    if (punctuation !in ".!?") return false

    if (punctuation == '.') {
        val precedingToken = extractSentenceBoundaryToken(text, punctuationIndex)
        if (precedingToken.lowercase() in sentenceBoundaryAbbreviations) return false
    }

    val nextNonWhitespaceIndex = text.indexOfFirstFrom(whitespaceIndex + 1) { !it.isWhitespace() }
    if (nextNonWhitespaceIndex == -1) return true

    val nextChar = text[nextNonWhitespaceIndex]
    return nextChar.isUpperCase() || nextChar.isDigit() || nextChar in "\"'(["
}

private fun extractSentenceBoundaryToken(text: String, punctuationIndex: Int): String {
    var start = punctuationIndex - 1
    while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '.')) {
        start--
    }
    return text.substring(start + 1, punctuationIndex)
}

private inline fun CharSequence.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}

private fun buildResultNotificationTitle(
    context: Context,
    displayName: String,
    resultKind: BackgroundShareResultKind,
): String = when (resultKind) {
    BackgroundShareResultKind.TRANSCRIPTION ->
        context.getString(R.string.background_share_transcription_ready_title, displayName)
    BackgroundShareResultKind.SUMMARY ->
        context.getString(R.string.background_share_summary_ready_title, displayName)
}

private fun buildResultPageLabel(context: Context, page: BackgroundShareResultPage): String? = if (page.total > 1) {
    context.getString(R.string.background_share_result_part_label, page.index + 1, page.total)
} else {
    null
}

internal fun buildResultPageIntentData(
    packageName: String,
    jobId: Int,
    resultKind: BackgroundShareResultKind,
    pageIndex: Int,
    action: String,
): String = buildString {
    append("whispertranscriber://")
    append(packageName)
    append("/background-share-result-page/")
    append(action)
    append("?jobId=")
    append(jobId)
    append("&resultKind=")
    append(resultKind.name)
    append("&pageIndex=")
    append(pageIndex)
}

private fun createResultPagePendingIntent(
    context: Context,
    jobId: Int,
    displayName: String,
    resultKind: BackgroundShareResultKind,
    pageIndex: Int,
    action: String,
): PendingIntent {
    val intent = Intent(context, BackgroundShareResultNotificationReceiver::class.java).apply {
        this.action = action
        data = buildResultPageIntentData(
            packageName = context.packageName,
            jobId = jobId,
            resultKind = resultKind,
            pageIndex = pageIndex,
            action = action,
        ).toUri()
        putExtra(BackgroundShareResultNotificationReceiver.EXTRA_JOB_ID, jobId)
        putExtra(BackgroundShareResultNotificationReceiver.EXTRA_DISPLAY_NAME, displayName)
        putExtra(BackgroundShareResultNotificationReceiver.EXTRA_RESULT_KIND, resultKind.name)
        putExtra(BackgroundShareResultNotificationReceiver.EXTRA_PAGE_INDEX, pageIndex)
    }
    return PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun buildResultNotification(
    context: Context,
    displayName: String,
    resultKind: BackgroundShareResultKind,
    page: BackgroundShareResultPage,
    jobId: Int,
    openAppPendingIntent: PendingIntent,
): NotificationCompat.Builder {
    val pageLabel = buildResultPageLabel(context, page)
    return NotificationCompat.Builder(context, BackgroundShareNotifier.RESULT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(buildResultNotificationTitle(context, displayName, resultKind))
        .setContentText(page.text.trim())
        .setSubText(pageLabel)
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(page.text)
                .setSummaryText(pageLabel),
        )
        .setContentIntent(openAppPendingIntent)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setGroup(BackgroundShareNotifier.RESULT_GROUP_KEY)
        .apply {
            if (page.hasPrevious) {
                addAction(
                    0,
                    context.getString(R.string.background_share_result_previous),
                    createResultPagePendingIntent(
                        context = context,
                        jobId = jobId,
                        displayName = displayName,
                        resultKind = resultKind,
                        pageIndex = page.index - 1,
                        action = BackgroundShareResultNotificationReceiver.ACTION_PREVIOUS_RESULT_PAGE,
                    ),
                )
            }
            if (page.hasNext) {
                addAction(
                    0,
                    context.getString(R.string.background_share_result_next),
                    createResultPagePendingIntent(
                        context = context,
                        jobId = jobId,
                        displayName = displayName,
                        resultKind = resultKind,
                        pageIndex = page.index + 1,
                        action = BackgroundShareResultNotificationReceiver.ACTION_NEXT_RESULT_PAGE,
                    ),
                )
            }
        }
}

@Singleton
class BackgroundShareNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        internal const val RESULT_CHANNEL_ID = "background_share_results"
        private const val RESULT_CHANNEL_NAME = "Shared audio results"
        internal const val RESULT_GROUP_KEY = "background_share_result_group"
        private const val PROCESSING_GROUP_KEY = "background_share_processing_group"
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
        showResultPage(
            jobId = jobId,
            displayName = displayName,
            resultKind = BackgroundShareResultKind.SUMMARY,
            text = summary,
        )
    }

    fun showTranscription(jobId: Int, displayName: String, transcript: String) {
        showResultPage(
            jobId = jobId,
            displayName = displayName,
            resultKind = BackgroundShareResultKind.TRANSCRIPTION,
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
            builder = processingBuilder(
                jobId = jobId,
                title = context.getString(R.string.transcription_retry_notification_title, displayName),
                message = message,
            ),
        )
    }

    fun showBackgroundProcessing(jobId: Int, displayName: String, message: String) {
        notify(
            id = processingNotificationId(jobId),
            builder = processingBuilder(
                jobId = jobId,
                title = context.getString(R.string.transcription_background_notification_title, displayName),
                message = message,
            ),
        )
    }

    fun showPreparingShare() {
        val displayName = context.getString(R.string.background_share_generic_audio_label)
        val message = context.getString(R.string.background_share_preparing_notification_body)
        notify(
            id = PREPARING_SHARE_NOTIFICATION_ID,
            builder = processingBuilder(
                jobId = null,
                title = context.getString(R.string.transcription_background_notification_title, displayName),
                message = message,
            ),
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

    fun cancelResult(jobId: Int) {
        NotificationManagerCompat.from(context).cancel(resultNotificationId(jobId))
    }

    internal fun showResultPage(
        jobId: Int,
        displayName: String,
        resultKind: BackgroundShareResultKind,
        text: String,
        pageIndex: Int = 0,
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            cancelResult(jobId)
            return
        }

        val page = resolveNotificationTextPage(normalizedText, pageIndex)
        notify(
            id = resultNotificationId(jobId),
            builder = buildResultNotification(
                context = context,
                displayName = displayName,
                resultKind = resultKind,
                page = page,
                jobId = jobId,
                openAppPendingIntent = createOpenAppPendingIntent(),
            ),
        )
    }

    fun buildProcessingNotification(jobId: Int, displayName: String, message: String): Notification = processingBuilder(
        jobId = jobId,
        title = context.getString(R.string.transcription_background_notification_title, displayName),
        message = message,
    ).build()

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

    private fun createCancelJobPendingIntent(jobId: Int): PendingIntent {
        val intent = Intent(context, CancelTranscriptionNotificationReceiver::class.java).apply {
            action = CancelTranscriptionNotificationReceiver.ACTION_CANCEL_JOB
            putExtra(CancelTranscriptionNotificationReceiver.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(
            context,
            jobId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun processingBuilder(jobId: Int?, title: String, message: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, TranscriptionWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(createOpenAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setGroup(PROCESSING_GROUP_KEY)
            .apply {
                if (jobId != null) {
                    addAction(
                        R.drawable.ic_notification,
                        context.getString(R.string.stop_transcription),
                        createCancelJobPendingIntent(jobId),
                    )
                }
            }

    private fun genericFailureNotificationId(): Int = GENERIC_FAILURE_NOTIFICATION_OFFSET + Random.nextInt(10_000)

    private fun retryNotificationId(jobId: Int): Int = RETRY_NOTIFICATION_OFFSET + jobId

    private fun processingNotificationId(jobId: Int): Int = PROCESSING_NOTIFICATION_OFFSET + jobId

    private fun resultNotificationId(jobId: Int): Int = RESULT_NOTIFICATION_OFFSET + jobId
}
