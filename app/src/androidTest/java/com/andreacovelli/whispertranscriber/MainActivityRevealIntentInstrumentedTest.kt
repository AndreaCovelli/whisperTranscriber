package com.andreacovelli.whispertranscriber

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.ui.main.MainViewModel
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRevealIntentInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var activity: MainActivity

    @Before
    fun launchMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as MainActivity
        instrumentation.waitForIdleSync()
    }

    @After
    fun finishSingleTaskActivity() {
        composeRule.runOnUiThread {
            activity.finishAndRemoveTask()
        }
        composeRule.waitUntil { activity.isDestroyed }
    }

    @Test
    fun foregroundShareReceivedFromSettings_returnsToMainImmediately() {
        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_settings),
        ).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.settings_title)).assertIsDisplayed()

        deliverForegroundShare("settings-share.wav")

        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_settings),
        ).assertIsDisplayed()
    }

    @Test
    fun foregroundShareReceivedFromHistory_returnsToMainImmediately() {
        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_history),
        ).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.history_title)).assertIsDisplayed()

        deliverForegroundShare("history-share.wav")

        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_history),
        ).assertIsDisplayed()
    }

    @Test
    fun missingBackgroundResultTarget_opensMainAndClearsRevealRequest() {
        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_settings),
        ).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.settings_title)).assertIsDisplayed()

        deliverToMain(
            Intent(activity, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_BACKGROUND_SHARE_RESULT_JOB_ID, Int.MAX_VALUE)
            },
        )

        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.main_open_settings),
        ).assertIsDisplayed()
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        composeRule.waitUntil {
            viewModel.uiState.value.jobsLoaded &&
                viewModel.uiState.value.mainNavigationRequest == null &&
                viewModel.uiState.value.jobRevealRequest == null
        }
    }

    private fun deliverForegroundShare(displayName: String) {
        val uri = Uri.parse("content://com.andreacovelli.whispertranscriber.test/$displayName")
        deliverToMain(
            Intent(Intent.ACTION_SEND, uri, activity, MainActivity::class.java).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
            },
        )
    }

    private fun deliverToMain(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        composeRule.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantNotificationPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = instrumentation.targetContext.packageName
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}",
            )
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
}
