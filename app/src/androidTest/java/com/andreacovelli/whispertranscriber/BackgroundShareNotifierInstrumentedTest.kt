package com.andreacovelli.whispertranscriber

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val JOB_ID = 4_242
private const val LEGACY_RESULT_NOTIFICATION_ID = 20_000 + JOB_ID
private const val NOTIFICATION_WAIT_MILLIS = 5_000L
private const val NOTIFICATION_POLL_MILLIS = 50L

@RunWith(AndroidJUnit4::class)
class BackgroundShareNotifierInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: BackgroundShareNotifier

    @Before
    fun setUp() {
        grantNotificationPermission()
        notifier = BackgroundShareNotifier(context)
    }

    @After
    fun tearDown() {
        notificationManager.cancel(LEGACY_RESULT_NOTIFICATION_ID)
    }

    @Test
    fun cancelResult_removesLegacyUntaggedResultNotification() {
        notificationManager.notify(
            LEGACY_RESULT_NOTIFICATION_ID,
            Notification.Builder(context, BackgroundShareNotifier.RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Legacy result")
                .build(),
        )
        assertTrue(awaitLegacyResultNotification(present = true))

        notifier.cancelResult(JOB_ID)

        assertTrue(awaitLegacyResultNotification(present = false))
    }

    private fun awaitLegacyResultNotification(present: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + NOTIFICATION_WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (isLegacyResultNotificationActive() == present) return true
            SystemClock.sleep(NOTIFICATION_POLL_MILLIS)
        }
        return false
    }

    private fun isLegacyResultNotificationActive(): Boolean = notificationManager.activeNotifications.any { active ->
        active.tag == null && active.id == LEGACY_RESULT_NOTIFICATION_ID
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
