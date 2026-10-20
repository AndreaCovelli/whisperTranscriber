package com.andreacovelli.whispertranscriber.ui.main

import android.net.Uri
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState

/** What the main screen shows about the configured providers; the settings screen owns the details. */
data class ProviderSummary(
    val transcriptionProvider: TranscriptionProvider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
    val transcriptionCustomModelName: String = "",
    val summarizerProvider: TranscriptionProvider = TranscriptionProvider.GEMINI_FLASH,
    val summarizerCustomModelName: String = "",
    val canSummarize: Boolean = false,
)

data class MainUiState(
    val status: UserMessage = UserMessage(R.string.main_waiting_for_audios),
    val providerSummary: ProviderSummary = ProviderSummary(),
    val jobs: List<TranscriptionJobState> = emptyList(),
    val batchProgressState: BatchProgressState? = null,
    val jobsLoaded: Boolean = false,
    val latestRevealRequestToken: Long = 0L,
    val mainNavigationRequest: MainNavigationRequest? = null,
    val jobRevealRequest: JobRevealRequest? = null,
)

data class MainNavigationRequest(val token: Long)

sealed interface JobRevealRequest {
    val token: Long

    data class AwaitingCreation(override val token: Long) : JobRevealRequest

    data class AwaitingPresentation(override val token: Long, val jobId: Int, val clearWhenAbsent: Boolean) :
        JobRevealRequest

    data class Ready(override val token: Long, val jobId: Int) : JobRevealRequest
}

/** User actions from the main screen, plus the results the view model reports back. */
sealed interface MainMsg {
    data class SharedAudioReceived(val uris: List<Uri>) : MainMsg
    data class SharedAudioQueueFinished(val requestToken: Long, val firstCreatedJobId: Int?) : MainMsg
    data class BackgroundShareResultOpened(val jobId: Int) : MainMsg
    data class MainNavigationHandled(val requestToken: Long) : MainMsg
    data class JobRevealHandled(val requestToken: Long) : MainMsg
    data object ClearCompletedJobs : MainMsg
    data class SummarizeJob(val jobId: Int) : MainMsg
    data class ClearSummary(val jobId: Int) : MainMsg
    data class ClearTranscript(val jobId: Int) : MainMsg
    data class RetryJob(val jobId: Int) : MainMsg
    data class CancelJob(val jobId: Int) : MainMsg
    data class CancelSummary(val jobId: Int) : MainMsg
    data class DeleteJob(val jobId: Int) : MainMsg

    data class StatusChanged(val status: UserMessage) : MainMsg
    data class ProviderSummaryChanged(val summary: ProviderSummary) : MainMsg
    data class JobPresentationChanged(
        val jobs: List<TranscriptionJobState>,
        val batchProgressState: BatchProgressState?,
    ) : MainMsg
}

/** Pure state transitions; queue work runs in [MainViewModel]. */
internal object MainReducer {
    fun reduce(state: MainUiState, msg: MainMsg): MainUiState = when (msg) {
        is MainMsg.SharedAudioReceived -> requestForegroundShare(state, msg)
        is MainMsg.SharedAudioQueueFinished -> finishForegroundShare(state, msg)
        is MainMsg.BackgroundShareResultOpened -> requestBackgroundResultReveal(state, msg.jobId)
        is MainMsg.MainNavigationHandled -> if (state.mainNavigationRequest?.token == msg.requestToken) {
            state.copy(mainNavigationRequest = null)
        } else {
            state
        }
        is MainMsg.JobRevealHandled -> if (
            state.jobRevealRequest is JobRevealRequest.Ready &&
            state.jobRevealRequest.token == msg.requestToken
        ) {
            state.copy(jobRevealRequest = null)
        } else {
            state
        }
        is MainMsg.StatusChanged -> state.copy(status = msg.status)
        is MainMsg.ProviderSummaryChanged -> state.copy(providerSummary = msg.summary)
        is MainMsg.JobPresentationChanged -> resolveJobRevealAfterPresentation(
            state.copy(
                jobs = msg.jobs,
                batchProgressState = msg.batchProgressState,
                jobsLoaded = true,
            ),
        )
        else -> state
    }

    private fun requestForegroundShare(state: MainUiState, msg: MainMsg.SharedAudioReceived): MainUiState {
        if (msg.uris.isEmpty()) return state
        val requestToken = state.latestRevealRequestToken + 1
        return state.copy(
            latestRevealRequestToken = requestToken,
            mainNavigationRequest = MainNavigationRequest(requestToken),
            jobRevealRequest = JobRevealRequest.AwaitingCreation(requestToken),
        )
    }

    private fun finishForegroundShare(state: MainUiState, msg: MainMsg.SharedAudioQueueFinished): MainUiState {
        val currentRequest = state.jobRevealRequest
        if (currentRequest !is JobRevealRequest.AwaitingCreation || currentRequest.token != msg.requestToken) {
            return state
        }
        val jobId = msg.firstCreatedJobId ?: return state.copy(jobRevealRequest = null)
        return state.copy(
            jobRevealRequest = if (state.jobs.any { it.id == jobId }) {
                JobRevealRequest.Ready(msg.requestToken, jobId)
            } else {
                JobRevealRequest.AwaitingPresentation(
                    token = msg.requestToken,
                    jobId = jobId,
                    clearWhenAbsent = false,
                )
            },
        )
    }

    private fun requestBackgroundResultReveal(state: MainUiState, jobId: Int): MainUiState {
        if (jobId < 0) return state
        val requestToken = state.latestRevealRequestToken + 1
        return resolveJobRevealAfterPresentation(
            state.copy(
                latestRevealRequestToken = requestToken,
                mainNavigationRequest = MainNavigationRequest(requestToken),
                jobRevealRequest = JobRevealRequest.AwaitingPresentation(
                    token = requestToken,
                    jobId = jobId,
                    clearWhenAbsent = true,
                ),
            ),
        )
    }

    private fun resolveJobRevealAfterPresentation(state: MainUiState): MainUiState {
        val request = state.jobRevealRequest ?: return state
        return when (request) {
            is JobRevealRequest.AwaitingCreation -> state
            is JobRevealRequest.AwaitingPresentation -> when {
                state.jobs.any { it.id == request.jobId } -> state.copy(
                    jobRevealRequest = JobRevealRequest.Ready(request.token, request.jobId),
                )
                state.jobsLoaded && request.clearWhenAbsent -> state.copy(jobRevealRequest = null)
                else -> state
            }
            is JobRevealRequest.Ready -> if (state.jobs.any { it.id == request.jobId }) {
                state
            } else {
                state.copy(jobRevealRequest = null)
            }
        }
    }
}
