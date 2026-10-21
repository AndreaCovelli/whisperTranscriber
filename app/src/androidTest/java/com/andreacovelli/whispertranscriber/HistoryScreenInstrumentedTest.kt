package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryDateSource
import com.andreacovelli.whispertranscriber.history.HistoryGroupingContext
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.components.TRANSCRIPT_EDIT_FIELD_TAG
import com.andreacovelli.whispertranscriber.ui.history.HistoryViewModel
import com.andreacovelli.whispertranscriber.ui.screens.HistoryScreen
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs [HistoryScreen] on a real [HistoryViewModel] backed by an in-memory database. */
@RunWith(AndroidJUnit4::class)
class HistoryScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var settings: IsolatedSettings
    private lateinit var fixture: RepairTestFixture
    private lateinit var viewModel: HistoryViewModel
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        settings = IsolatedSettings(context)
        fixture = RepairTestFixture(context, settings = settings.manager)
        val today = HistoryGroupingContext(LocalDate.of(2026, 4, 15), ZoneId.of("UTC"))
        val dates = object : HistoryDateSource {
            override val changes = MutableStateFlow(today)
        }
        viewModel =
            HistoryViewModel(
                context,
                fixture.history,
                fixture.queue,
                TextTransfer(context, Dispatchers.IO),
                dates,
                applicationScope,
            )
    }

    @After
    fun tearDown() {
        // Join, not just cancel: an in-flight Room query would otherwise outlive the closed database.
        runBlocking {
            withContext(Dispatchers.Main) { viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
            applicationScope.coroutineContext.job.children.forEach { it.join() }
        }
        fixture.close()
        settings.close()
    }

    @Test
    fun searchField_filtersResultsAndShowsTheEmptyResultsState() {
        insertHistory("Alpha planning meeting", timestamp = 2_000L)
        insertHistory("Weekly review", timestamp = 1_000L)
        setHistoryScreen()
        awaitText("Weekly review")
        val search = composeRule.onNode(hasSetTextAction())

        search.performTextReplacement("ALPHA")
        awaitGone("Weekly review")
        composeRule.onNodeWithText("Alpha planning meeting", substring = true).assertIsDisplayed()

        search.performTextReplacement("nomatch")
        val noResults = context.getString(R.string.history_no_results, "nomatch")
        awaitText(noResults)
        composeRule.onNodeWithText(noResults).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.history_clear_search)).performClick()
        awaitText("Weekly review")
        composeRule.onNodeWithText("Alpha planning meeting", substring = true).assertIsDisplayed()
    }

    @Test
    fun deleteAllDialog_canBeDismissedOrConfirmed() {
        insertHistory("Transcript to delete")
        setHistoryScreen()
        awaitText("Transcript to delete")
        val deleteAll = context.getString(R.string.history_delete_all_description)
        val dialogTitle = context.getString(R.string.history_delete_all_title)

        composeRule.onNodeWithContentDescription(deleteAll).performClick()
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
        composeRule.onNodeWithText("Transcript to delete", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(deleteAll).performClick()
        composeRule.onNodeWithText(context.getString(R.string.history_delete_all_confirm)).performClick()

        awaitText(context.getString(R.string.history_empty))
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(deleteAll).assertDoesNotExist()
        assertFalse(runBlocking { fixture.history.hasHistory() })
    }

    @Test
    fun editTranscriptDialog_savesTheCorrectedTranscript() {
        insertHistory("Transcript with a typpo")
        setHistoryScreen()
        awaitText("Transcript with a typpo")

        composeRule.onNodeWithText(context.getString(R.string.history_edit_transcript)).performClick()
        composeRule.onNodeWithTag(TRANSCRIPT_EDIT_FIELD_TAG).performTextReplacement("Transcript with a typo")
        composeRule.onNodeWithText(context.getString(R.string.history_edit_transcript_save)).performClick()

        awaitText("Transcript with a typo")
        composeRule.onNodeWithTag(TRANSCRIPT_EDIT_FIELD_TAG).assertDoesNotExist()
        awaitGone("typpo")
    }

    @Test
    fun deleteItem_offersUndoThatRestoresTheItem() {
        insertHistory("Transcript to keep")
        setHistoryScreen()
        awaitText("Transcript to keep")

        composeRule.onNodeWithContentDescription(context.getString(R.string.delete_item_description)).performClick()
        awaitText(context.getString(R.string.history_item_deleted))
        composeRule.onNodeWithText("Transcript to keep", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.history_undo)).performClick()

        awaitText("Transcript to keep")
        assertTrue(runBlocking { fixture.history.hasHistory() })
    }

    @Test
    fun deleteItem_deletesWhenTheUndoOfferEnds() {
        insertHistory("Transcript to delete")
        setHistoryScreen()
        awaitText("Transcript to delete")

        composeRule.onNodeWithContentDescription(context.getString(R.string.delete_item_description)).performClick()
        awaitText(context.getString(R.string.history_item_deleted))

        composeRule.waitUntil(UNDO_TIMEOUT_MILLIS) { !runBlocking { fixture.history.hasHistory() } }
        awaitGone(context.getString(R.string.history_item_deleted))
        awaitText(context.getString(R.string.history_empty))
    }

    @Test
    fun exportDialog_offersEveryFormatAndClosesWhenOneIsChosenOrCancelled() {
        setHistoryScreen()
        awaitText(context.getString(R.string.history_empty))
        val exportAction = context.getString(R.string.history_export_action)
        val dialogTitle = context.getString(R.string.history_export_dialog_title)
        val formats = listOf(
            R.string.history_export_format_txt,
            R.string.history_export_format_csv,
            R.string.history_export_format_json,
        ).map(context::getString)

        composeRule.onNodeWithText(exportAction).performClick()
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        formats.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText(context.getString(R.string.history_export_cancel)).performClick()
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()

        // With no history the choice only shows a toast, so no document picker opens.
        composeRule.onNodeWithText(exportAction).performClick()
        composeRule.onNodeWithText(formats.first()).performClick()
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.history_empty)).assertIsDisplayed()
    }

    private fun setHistoryScreen() {
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(historyViewModel = viewModel, onNavigateBack = {})
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(text: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun insertHistory(text: String, timestamp: Long = 1_000L) = runBlocking {
        fixture.history.insert(
            TranscriptionHistory(text = text, language = "en", displayName = "audio.wav", timestamp = timestamp),
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        // A short snackbar stays up for four seconds, or longer when accessibility services ask for it.
        const val UNDO_TIMEOUT_MILLIS = 30_000L
    }
}
