package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.ui.screens.SettingsScreenContent
import com.andreacovelli.whispertranscriber.ui.settings.CustomModelValidation
import com.andreacovelli.whispertranscriber.ui.settings.RegionValidationState
import com.andreacovelli.whispertranscriber.ui.settings.SettingsMsg
import com.andreacovelli.whispertranscriber.ui.settings.SettingsUiState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val messages = mutableListOf<SettingsMsg>()
    private var uiState by mutableStateOf(SettingsUiState())
    private lateinit var focusManager: FocusManager

    @Test
    fun providerOptions_reportTheSelectedProviderForEachSelector() {
        setSettings(
            SettingsUiState(
                provider = TranscriptionProvider.GEMINI_FLASH,
                summarizerProvider = TranscriptionProvider.GEMINI_FLASH,
            ),
        )
        val microsoft = context.getString(R.string.provider_option_microsoft_speech)
        val pro = context.getString(R.string.provider_option_pro)
        composeRule.onNodeWithText(microsoft).performScrollTo().assertIsNotSelected()
        composeRule.onAllNodesWithText(context.getString(R.string.provider_option_flash))[0].assertIsSelected()

        composeRule.onNodeWithText(microsoft).performClick()
        composeRule.onAllNodesWithText(pro)[1].performScrollTo().performClick()

        assertEquals(
            listOf(
                SettingsMsg.SetProvider(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE),
                SettingsMsg.SetSummarizerProvider(TranscriptionProvider.GEMINI_PRO),
            ),
            messages,
        )
        composeRule.runOnIdle { uiState = uiState.copy(provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE) }
        composeRule.onNodeWithText(microsoft).assertIsSelected()
    }

    @Test
    fun customModelField_showsEachValidationState() {
        setSettings(
            SettingsUiState(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                transcriptionCustomGeminiModelName = "gemini-test",
            ),
        )
        val validating = context.getString(R.string.provider_validating)
        // Plain status texts merge into the clickable scroll column, so they are matched in the unmerged tree.
        composeRule.onNodeWithText(validating, useUnmergedTree = true).assertDoesNotExist()

        composeRule.runOnIdle {
            uiState = uiState.copy(transcriptionCustomModelValidation = CustomModelValidation.Validating)
        }
        composeRule.onNodeWithText(validating, useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(transcriptionCustomModelValidation = CustomModelValidation.Valid("Gemini Test"))
        }
        composeRule.onNodeWithText(validating, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Gemini Test", useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(
                transcriptionCustomModelValidation = CustomModelValidation.Error(
                    UserMessage.verbatim("Model gemini-test was not found"),
                ),
            )
        }
        composeRule.onNodeWithText("Gemini Test", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Model gemini-test was not found", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("gemini-test")).assert(isError)
    }

    @Test
    fun customModelField_resetsWhileTypingAndValidatesWhenFocusLeaves() {
        setSettings(SettingsUiState(provider = TranscriptionProvider.GEMINI_CUSTOM))

        composeRule.onNodeWithText(context.getString(R.string.provider_custom_placeholder))
            .performClick()
            .performTextReplacement("gemini-new")
        composeRule.runOnIdle { focusManager.clearFocus() }

        composeRule.runOnIdle {
            assertEquals(SettingsMsg.ResetTranscriptionModelValidation, messages.first())
            assertEquals(SettingsMsg.ValidateTranscriptionModel("gemini-new"), messages.last())
        }
    }

    @Test
    fun microsoftRegionField_showsEachValidationStateAndSavesWhenFocusLeaves() {
        setSettings(SettingsUiState(microsoftSpeechRegion = "westus"))
        val validating = context.getString(R.string.settings_microsoft_speech_region_validating)
        val valid = context.getString(R.string.settings_microsoft_speech_region_valid, "eastus")

        composeRule.runOnIdle {
            uiState = uiState.copy(microsoftSpeechRegionValidation = RegionValidationState.Validating)
        }
        composeRule.onNodeWithText(validating, useUnmergedTree = true).performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(microsoftSpeechRegionValidation = RegionValidationState.Valid("eastus"))
        }
        composeRule.onNodeWithText(validating, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(valid, useUnmergedTree = true).performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(
                microsoftSpeechRegionValidation = RegionValidationState.Error(UserMessage.verbatim("Unknown region")),
            )
        }
        composeRule.onNodeWithText(valid, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Unknown region", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("westus")).assert(isError)

        composeRule.onNode(hasSetTextAction() and hasText("westus"))
            .performScrollTo()
            .performClick()
            .performTextReplacement("westus2")
        composeRule.runOnIdle { focusManager.clearFocus() }

        composeRule.runOnIdle {
            assertEquals(SettingsMsg.ResetMicrosoftSpeechRegionValidation, messages.first())
            assertEquals(SettingsMsg.SetMicrosoftSpeechRegion("westus2"), messages.last())
        }
    }

    @Test
    fun transcriptionHints_selectLanguageAndSaveVocabularyWhenFocusLeaves() {
        setSettings(SettingsUiState())
        val automatic = context.getString(R.string.settings_transcription_language_automatic)
        val italian = Locale.forLanguageTag("it").getDisplayName(context.resources.configuration.locales[0])

        composeRule.onNode(hasText(automatic) and hasClickAction()).performScrollTo().performClick()
        composeRule.onNodeWithText(italian).performScrollTo().performClick()
        composeRule.runOnIdle { uiState = uiState.copy(transcriptionLanguage = "it") }
        composeRule.onNode(hasText(italian) and hasClickAction()).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.settings_transcription_vocabulary_label))
            .performScrollTo()
            .performClick()
            .performTextReplacement("Covelli, Kotlin")
        composeRule.runOnIdle { focusManager.clearFocus() }

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsMsg.SetTranscriptionLanguage("it"),
                    SettingsMsg.SetTranscriptionVocabulary("Covelli, Kotlin"),
                ),
                messages,
            )
        }
    }

    private fun setSettings(initial: SettingsUiState) {
        uiState = initial
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                SettingsScreenContent(
                    uiState = uiState,
                    dispatch = { messages += it },
                    onLoadMicrosoftSpeechKey = { null },
                    onLoadGeminiKey = { null },
                    onNavigateBack = {},
                    onBackgroundShareSummaryNotificationsChange = {},
                )
            }
        }
    }

    private companion object {
        val isError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    }
}
