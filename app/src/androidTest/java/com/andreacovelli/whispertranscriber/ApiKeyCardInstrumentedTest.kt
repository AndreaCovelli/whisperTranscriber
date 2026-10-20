package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.ui.components.ApiKeyCard
import com.andreacovelli.whispertranscriber.ui.settings.KeyValidationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()
    private lateinit var focusManager: FocusManager

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

    @Test
    fun visibilityToggle_showsAndHidesTheTypedKey() {
        setCard(ApiKeyState.Missing)
        val show = context.getString(R.string.api_key_show)
        val hide = context.getString(R.string.api_key_hide)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("typed-test-value")
        composeRule.onNode(showsText("typed-test-value")).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNode(showsText("typed-test-value")).assertExists()
        composeRule.onNodeWithContentDescription(show).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(hide).performClick()
        composeRule.onNode(showsText("typed-test-value")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(show).assertExists()
    }

    @Test
    fun typedKey_resetsValidationAndIsSavedWhenFocusLeaves() {
        setCard(ApiKeyState.Missing)

        composeRule.onNode(hasSetTextAction()).performClick().performTextReplacement("typed-test-value")
        composeRule.runOnIdle { focusManager.clearFocus() }

        composeRule.runOnIdle {
            assertEquals(listOf("reset", "save:typed-test-value"), calls)
        }
    }

    @Test
    fun forget_reportsTheActionAndClearsTheTypedKey() {
        setCard(ApiKeyState.Present)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("typed-test-value")
        composeRule.onNodeWithContentDescription(context.getString(R.string.api_key_show)).performClick()
        composeRule.onNode(showsText("typed-test-value")).assertExists()

        composeRule.onNodeWithText(context.getString(R.string.api_key_forget)).performClick()

        composeRule.onNode(showsText("typed-test-value")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.api_key_show)).assertExists()
        composeRule.runOnIdle { assertEquals("forget", calls.last()) }
    }

    @Test
    fun status_showsValidationProgressErrorsAndTheSavedState() {
        var validation by mutableStateOf<KeyValidationState>(KeyValidationState.Idle)
        var keyState by mutableStateOf<ApiKeyState>(ApiKeyState.Present)
        setCard({ keyState }, { validation })
        val saved = context.getString(R.string.api_key_encrypted_saved)
        composeRule.onNodeWithText(saved).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.api_key_replace_saved_optional)).assertExists()

        composeRule.runOnIdle { validation = KeyValidationState.Validating }
        composeRule.onNodeWithText(context.getString(R.string.api_key_validating)).assertIsDisplayed()
        composeRule.onNodeWithText(saved).assertDoesNotExist()

        composeRule.runOnIdle { validation = KeyValidationState.Error(UserMessage.verbatim("Key rejected")) }
        composeRule.onNodeWithText("Key rejected").assertIsDisplayed()

        composeRule.runOnIdle {
            validation = KeyValidationState.Idle
            keyState = ApiKeyState.Error(UserMessage.verbatim("Key unreadable"))
        }
        composeRule.onNodeWithText("Key rejected").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.api_key_keystore_error, "Key unreadable"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Enter key").assertExists()
    }

    /** The field's displayed text; its raw input is exposed even while masked. */
    private fun showsText(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))

    private fun setCard(keyState: ApiKeyState) = setCard({ keyState }, { KeyValidationState.Idle })

    private fun setCard(keyState: () -> ApiKeyState, validation: () -> KeyValidationState) {
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                ApiKeyCard(
                    title = "Key",
                    label = "Enter key",
                    apiKeyState = keyState(),
                    keyValidationState = validation(),
                    onLoadSavedKey = { null },
                    onSave = { calls += "save:$it" },
                    onForget = { calls += "forget" },
                    onResetValidation = { calls += "reset" },
                )
            }
        }
    }
}
