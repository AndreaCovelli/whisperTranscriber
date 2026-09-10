package com.andreacovelli.whispertranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionRecreationInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun permissionResultAfterRecreation_enablesTheRequestedBackgroundPreference() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Run this test separately on a fresh managed device too: other UI tests grant this permission.
        assumeTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED,
        )
        val settings = ApiKeyManager(context)
        settings.saveBackgroundShareSummaryNotificationsEnabled(false)
        val original = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        var recreated: MainActivity? = null
        try {
            clickPermissionButton("permission_deny_button")
            composeRule.onNodeWithContentDescription(context.getString(R.string.main_open_settings)).performClick()
            composeRule.onNodeWithContentDescription(
                context.getString(R.string.settings_background_share_summary_notifications),
            )
                .performScrollTo().performClick()
            composeRule.waitUntil(5_000) { permissionButton("permission_allow_button") != null }
            composeRule.runOnUiThread { original.recreate() }
            composeRule.waitUntil(5_000) {
                instrumentation.runOnMainSync {
                    recreated = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)
                        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                        .filterIsInstance<MainActivity>().firstOrNull { it !== original }
                }
                recreated != null
            }
            clickPermissionButton("permission_allow_button")
            assertTrue(withTimeout(5_000) { settings.backgroundShareSummaryNotificationsEnabledFlow.first { it } })
        } finally {
            settings.saveBackgroundShareSummaryNotificationsEnabled(false)
            composeRule.runOnUiThread { (recreated ?: original).finishAndRemoveTask() }
        }
    }

    private fun clickPermissionButton(id: String) {
        composeRule.waitUntil(5_000) { permissionButton(id) != null }
        assertTrue(checkNotNull(permissionButton(id)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun permissionButton(id: String): AccessibilityNodeInfo? =
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")?.firstOrNull()
}
