package com.andreacovelli.whispertranscriber.background

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
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.andreacovelli.whispertranscriber.BackgroundShareResultNotificationReceiver
import com.andreacovelli.whispertranscriber.CancelTranscriptionNotificationReceiver
import com.andreacovelli.whispertranscriber.MainActivity
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.TranscriptionWorker
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.BreakIterator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class PostTranscriptionWorkSpec(
    val summarizeAfterTranscription: Boolean,
    val notifyResult: Boolean,
    val summarizationSettings: SummarizationSettings,
)

internal const val RESULT_TEXT_PAGE_MAX_LENGTH = 420
internal const val RESULT_CACHE_MAX_ENTRIES = 20
internal const val RESULT_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
private const val LIST_ITEM_BREAK_LOOKAHEAD = 120

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

internal data class CachedShareResult(val displayName: String, val pages: List<String>)

internal class TimedLruCache<K, V>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(val value: V, val createdAt: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

    init {
        require(maxEntries > 0) { "Cache size must be positive." }
        require(ttlMillis > 0) { "Cache TTL must be positive." }
    }

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (nowMillis() - entry.createdAt >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        removeExpired()
        entries[key] = Entry(value, nowMillis())
        while (entries.size > maxEntries) {
            entries.remove(entries.entries.first().key)
        }
    }

    @Synchronized
    fun remove(key: K) {
        entries.remove(key)
    }

    @Synchronized
    fun removeMatching(predicate: (K) -> Boolean) {
        entries.keys.removeAll(predicate)
    }

    @Synchronized
    fun size(): Int {
        removeExpired()
        return entries.size
    }

    private fun removeExpired() {
        val now = nowMillis()
        entries.entries.removeAll { (_, entry) -> now - entry.createdAt >= ttlMillis }
    }
}

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
        val hardWindowEnd = minOf(start + maxChars, text.length)
        val softWindowEnd = if (maxChars > LIST_ITEM_BREAK_LOOKAHEAD) {
            minOf(start + (maxChars - LIST_ITEM_BREAK_LOOKAHEAD), text.length)
        } else {
            hardWindowEnd
        }
        if (softWindowEnd == text.length) {
            pages += text.substring(start)
            break
        }

        val splitEnd = findPreferredPageBreak(text, start, softWindowEnd, hardWindowEnd) ?: softWindowEnd
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

private fun findPreferredPageBreak(text: String, start: Int, softWindowEnd: Int, hardWindowEnd: Int): Int? {
    findListItemBreak(text, start, softWindowEnd, hardWindowEnd)?.let { return it }
    findParagraphBreak(text, start, softWindowEnd)?.let { return it }
    findSentenceBreak(text, start, softWindowEnd)?.let { return it }
    findLineBreak(text, start, softWindowEnd)?.let { return it }
    return findWordBreak(text, start, softWindowEnd)
}

private fun findListItemBreak(text: String, start: Int, softWindowEnd: Int, hardWindowEnd: Int): Int? {
    val listItemStarts = findListItemLineStarts(text, start, hardWindowEnd)
    if (listItemStarts.isEmpty()) return null

    val previousListItemStart = listItemStarts.lastOrNull { it <= softWindowEnd }
    if (previousListItemStart != null && previousListItemStart > start) {
        return previousListItemStart
    }

    return if (previousListItemStart != null) {
        listItemStarts.firstOrNull { it > softWindowEnd }
    } else {
        null
    }
}

private fun findListItemLineStarts(text: String, start: Int, endInclusive: Int): List<Int> {
    val result = mutableListOf<Int>()
    var lineStart = if (start == 0) {
        0
    } else {
        text.lastIndexOf('\n', start - 1).let { newlineIndex -> if (newlineIndex == -1) 0 else newlineIndex + 1 }
    }

    while (lineStart <= endInclusive && lineStart < text.length) {
        if (isListItemLineStart(text, lineStart)) {
            result += lineStart
        }

        val nextLineBreak = text.indexOf('\n', lineStart)
        if (nextLineBreak == -1) break
        lineStart = nextLineBreak + 1
    }

    return result
}

private fun isListItemLineStart(text: String, lineStart: Int): Boolean {
    var index = lineStart
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
        index++
    }
    if (index >= text.length) return false

    val marker = text[index]
    if ((marker == '-' || marker == '*' || marker == '•') && index + 1 < text.length &&
        text[index + 1].isWhitespace()
    ) {
        return true
    }

    if (!marker.isDigit()) return false

    var digitEnd = index + 1
    while (digitEnd < text.length && text[digitEnd].isDigit()) {
        digitEnd++
    }

    if (digitEnd >= text.length) return false
    val orderedMarker = text[digitEnd]
    return (orderedMarker == '.' || orderedMarker == ')') &&
        digitEnd + 1 < text.length &&
        text[digitEnd + 1].isWhitespace()
}

private fun findParagraphBreak(text: String, start: Int, windowEnd: Int): Int? {
    val index = text.lastIndexOf("\n\n", startIndex = windowEnd - 2)
    return if (index >= start) index + 2 else null
}

private fun findSentenceBreak(text: String, start: Int, windowEnd: Int): Int? {
    val iterator = BreakIterator.getSentenceInstance()
    iterator.setText(text)

    var boundary = iterator.preceding(windowEnd + 1)
    while (boundary != BreakIterator.DONE && boundary > start) {
        if (boundary <= windowEnd && isPreferredSentenceBoundary(text, start, boundary)) {
            return boundary
        }
        boundary = iterator.previous()
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

private fun isPreferredSentenceBoundary(text: String, start: Int, boundary: Int): Boolean {
    val punctuationIndex = text.lastIndexOfFirstNonWhitespace(before = boundary)
    if (punctuationIndex < start) return false

    if (text[punctuationIndex] != '.') return true

    val precedingToken = extractSentenceBoundaryToken(text, punctuationIndex)
    return precedingToken.lowercase() !in sentenceBoundaryAbbreviations
}

private fun extractSentenceBoundaryToken(text: String, punctuationIndex: Int): String {
    var start = punctuationIndex - 1
    while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '.')) {
        start--
    }
    return text.substring(start + 1, punctuationIndex)
}

private fun CharSequence.lastIndexOfFirstNonWhitespace(before: Int): Int {
    for (index in before - 1 downTo 0) {
        if (!this[index].isWhitespace()) return index
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

private fun createResultOpenAppPendingIntent(context: Context, jobId: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_BACKGROUND_SHARE_RESULT_JOB_ID, jobId)
    }
    return PendingIntent.getActivity(
        context,
        jobId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun createResultDismissPendingIntent(context: Context, jobId: Int): PendingIntent {
    val intent = Intent(context, BackgroundShareResultNotificationReceiver::class.java).apply {
        action = BackgroundShareResultNotificationReceiver.ACTION_DISMISS_RESULT_PAGE
        putExtra(BackgroundShareResultNotificationReceiver.EXTRA_JOB_ID, jobId)
    }
    return PendingIntent.getBroadcast(
        context,
        jobId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val PROCESSING_GROUP_KEY = "background_share_processing_group"

// Each notification kind has its own tag and uses the job ID as its ID, so kinds can never collide
// however large job IDs grow. Foreground-service notifications are untagged and unaffected.
private const val RESULT_NOTIFICATION_TAG = "result"
private const val GENERIC_FAILURE_NOTIFICATION_TAG = "generic_failure"
private const val RETRY_NOTIFICATION_TAG = "retry"
private const val PROCESSING_NOTIFICATION_TAG = "processing"
private const val PREPARING_SHARE_NOTIFICATION_TAG = "preparing_share"
private const val PREPARING_SHARE_NOTIFICATION_ID = 0

// Earlier versions posted untagged ongoing retry, processing, and preparing notifications from 60_000
// upwards. Foreground job notifications start at 200_000 and the worker uses 1001, so neither is affected.
private val LEGACY_ONGOING_NOTIFICATION_IDS = 60_000 until 200_000

/** Redacts the notification on secure lock screens when the user hides sensitive content. */
private fun NotificationCompat.Builder.setPrivateContent(
    context: Context,
    channelId: String,
    @StringRes publicTitle: Int,
): NotificationCompat.Builder = setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(publicTitle))
            .build(),
    )

private fun createOpenAppPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun createCancelJobPendingIntent(context: Context, jobId: Int): PendingIntent {
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

private fun buildResultNotification(
    context: Context,
    displayName: String,
    resultKind: BackgroundShareResultKind,
    page: BackgroundShareResultPage,
    jobId: Int,
): NotificationCompat.Builder {
    val pageLabel = buildResultPageLabel(context, page)
    val publicTitle = when (resultKind) {
        BackgroundShareResultKind.TRANSCRIPTION -> R.string.notification_public_transcription_ready
        BackgroundShareResultKind.SUMMARY -> R.string.notification_public_summary_ready
    }
    return NotificationCompat.Builder(context, BackgroundShareNotifier.RESULT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setPrivateContent(context, BackgroundShareNotifier.RESULT_CHANNEL_ID, publicTitle)
        .setContentTitle(buildResultNotificationTitle(context, displayName, resultKind))
        .setContentText(page.text.trim())
        .setSubText(pageLabel)
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(page.text.trim())
                .setSummaryText(pageLabel),
        )
        .setContentIntent(createResultOpenAppPendingIntent(context, jobId))
        .setDeleteIntent(createResultDismissPendingIntent(context, jobId))
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
        internal const val RESULT_GROUP_KEY = "background_share_result_group"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val pageCache = TimedLruCache<Pair<Int, BackgroundShareResultKind>, CachedShareResult>(
        maxEntries = RESULT_CACHE_MAX_ENTRIES,
        ttlMillis = RESULT_CACHE_TTL_MILLIS,
    )

    init {
        ensureResultChannel()
        ensureTranscriptionChannel()
        cancelLegacyOngoingNotifications()
        // No share is being prepared before the notifier exists, so this only removes one left
        // behind by a process that died while staging.
        cancelPreparingShare()
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
            tag = if (jobId != null) RESULT_NOTIFICATION_TAG else GENERIC_FAILURE_NOTIFICATION_TAG,
            id = jobId ?: Random.nextInt(),
            builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setPrivateContent(context, RESULT_CHANNEL_ID, R.string.notification_public_failed)
                .setContentTitle(context.getString(R.string.background_share_summary_failed_title, displayName))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createOpenAppPendingIntent(context))
                .setAutoCancel(true)
                .setGroup(RESULT_GROUP_KEY),
        )
    }

    fun showRetryScheduled(jobId: Int, displayName: String, message: String) {
        notify(
            tag = RETRY_NOTIFICATION_TAG,
            id = jobId,
            builder = processingBuilder(
                jobId = jobId,
                title = context.getString(R.string.transcription_retry_notification_title, displayName),
                message = message,
            ),
        )
    }

    fun showBackgroundProcessing(jobId: Int, displayName: String, message: String) {
        notify(
            tag = PROCESSING_NOTIFICATION_TAG,
            id = jobId,
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
            tag = PREPARING_SHARE_NOTIFICATION_TAG,
            id = PREPARING_SHARE_NOTIFICATION_ID,
            builder = processingBuilder(
                jobId = null,
                title = context.getString(R.string.transcription_background_notification_title, displayName),
                message = message,
            ),
        )
    }

    fun cancelRetryScheduled(jobId: Int) {
        NotificationManagerCompat.from(context).cancel(RETRY_NOTIFICATION_TAG, jobId)
    }

    fun cancelBackgroundProcessing(jobId: Int) {
        NotificationManagerCompat.from(context).cancel(PROCESSING_NOTIFICATION_TAG, jobId)
    }

    fun cancelPreparingShare() {
        notificationManager.cancel(PREPARING_SHARE_NOTIFICATION_TAG, PREPARING_SHARE_NOTIFICATION_ID)
    }

    fun cancelResult(jobId: Int) {
        clearCache(jobId)
        NotificationManagerCompat.from(context).cancel(RESULT_NOTIFICATION_TAG, jobId)
    }

    internal fun clearCache(jobId: Int) {
        pageCache.removeMatching { (cachedJobId, _) -> cachedJobId == jobId }
    }

    internal fun showCachedResultPage(jobId: Int, resultKind: BackgroundShareResultKind, pageIndex: Int): Boolean {
        val cached = pageCache[jobId to resultKind] ?: return false
        val resolvedIndex = pageIndex.coerceIn(0, cached.pages.lastIndex)
        val page = BackgroundShareResultPage(
            text = cached.pages[resolvedIndex],
            index = resolvedIndex,
            total = cached.pages.size,
        )
        notify(
            tag = RESULT_NOTIFICATION_TAG,
            id = jobId,
            builder = buildResultNotification(
                context = context,
                displayName = cached.displayName,
                resultKind = resultKind,
                page = page,
                jobId = jobId,
            ),
        )
        return true
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

        val pages = splitNotificationTextPages(normalizedText, RESULT_TEXT_PAGE_MAX_LENGTH)
        pageCache[jobId to resultKind] = CachedShareResult(displayName, pages)

        showCachedResultPage(jobId, resultKind, pageIndex)
    }

    fun buildProcessingNotification(jobId: Int, displayName: String, message: String): Notification = processingBuilder(
        jobId = jobId,
        title = context.getString(R.string.transcription_background_notification_title, displayName),
        message = message,
    ).build()

    @SuppressLint("MissingPermission")
    private fun notify(tag: String, id: Int, builder: NotificationCompat.Builder) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, builder.build())
        } catch (_: SecurityException) {
            // Notification permission may be revoked after the permission check.
        } catch (_: RuntimeException) {
            // A notification service failure must not undo durable queue work.
        }
    }

    private fun ensureResultChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                context.getString(R.string.notification_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun ensureTranscriptionChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TranscriptionWorker.CHANNEL_ID,
                context.getString(R.string.notification_channel_transcription),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun cancelLegacyOngoingNotifications() {
        try {
            notificationManager.activeNotifications
                .filter { notification ->
                    notification.tag == null &&
                        notification.id in LEGACY_ONGOING_NOTIFICATION_IDS &&
                        notification.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                }
                .forEach { notification -> notificationManager.cancel(notification.id) }
        } catch (_: RuntimeException) {
            // Best-effort cleanup must not block notifier construction.
        }
    }

    private fun processingBuilder(jobId: Int?, title: String, message: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, TranscriptionWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPrivateContent(context, TranscriptionWorker.CHANNEL_ID, R.string.notification_public_processing)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(createOpenAppPendingIntent(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setGroup(PROCESSING_GROUP_KEY)
            .apply {
                if (jobId != null) {
                    addAction(
                        R.drawable.ic_notification,
                        context.getString(R.string.stop_transcription),
                        createCancelJobPendingIntent(context, jobId),
                    )
                }
            }
}
