package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.ui.components.ApiKeyCard
import com.andreacovelli.whispertranscriber.ui.main.KeyValidationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun revealedValue_isRemovedWhenCredentialDisappearsOrScreenStops() {
        var keyState by mutableStateOf<ApiKeyState>(ApiKeyState.Present)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    ApiKeyCard("Key", "Enter key", keyState, KeyValidationState.Idle, {
                        "revealed-test-value"
                    }, {}, {}, {})
                }
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.api_key_show)).performClick()
        composeRule.onNodeWithText("revealed-test-value").assertExists()
        composeRule.runOnIdle { keyState = ApiKeyState.Missing }
        composeRule.onNodeWithText("revealed-test-value").assertDoesNotExist()
        composeRule.runOnIdle { keyState = ApiKeyState.Present }
        composeRule.onNodeWithContentDescription(context.getString(R.string.api_key_show)).performClick()
        composeRule.onNodeWithText("revealed-test-value").assertExists()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.onNodeWithText("revealed-test-value").assertDoesNotExist()
    }
}
