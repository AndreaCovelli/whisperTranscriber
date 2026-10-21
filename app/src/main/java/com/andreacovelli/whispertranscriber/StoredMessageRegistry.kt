package com.andreacovelli.whispertranscriber

/**
 * Stable codes for the messages that can be stored with a job. Resource IDs change between builds, so
 * stored messages name strings by these codes instead. A code starts as the resource name; keep it when the
 * string is renamed so that existing rows still resolve. StoredMessageRegistryTest fails when a message
 * created in the app is missing here.
 */
internal object StoredMessageRegistry {
    const val UNAVAILABLE_CODE = "stored_message_unavailable"

    private val strings: Map<String, Int> = mapOf(
        "api_key_decryption_failed" to R.string.api_key_decryption_failed,
        "api_key_keystore_error" to R.string.api_key_keystore_error,
        "background_share_interrupted_body" to R.string.background_share_interrupted_body,
        "background_share_queue_failed_body" to R.string.background_share_queue_failed_body,
        "background_share_waiting_to_start_notification_body" to
            R.string.background_share_waiting_to_start_notification_body,
        "conversion_audio_too_long" to R.string.conversion_audio_too_long,
        "conversion_insufficient_storage" to R.string.conversion_insufficient_storage,
        "conversion_no_audio_track" to R.string.conversion_no_audio_track,
        "conversion_no_decoder" to R.string.conversion_no_decoder,
        "conversion_no_samples" to R.string.conversion_no_samples,
        "conversion_output_too_large" to R.string.conversion_output_too_large,
        "conversion_unsupported_pcm_encoding" to R.string.conversion_unsupported_pcm_encoding,
        "custom_model_name_required" to R.string.custom_model_name_required,
        "gemini_api_key_missing" to R.string.gemini_api_key_missing,
        "gemini_audio_too_long" to R.string.gemini_audio_too_long,
        "gemini_key_invalid" to R.string.gemini_key_invalid,
        "gemini_key_required" to R.string.gemini_key_required,
        "gemini_model_name_invalid" to R.string.gemini_model_name_invalid,
        "gemini_model_not_found" to R.string.gemini_model_not_found,
        "gemini_model_not_specified" to R.string.gemini_model_not_specified,
        "gemini_model_validation_failed_http" to R.string.gemini_model_validation_failed_http,
        "gemini_quota_exhausted" to R.string.gemini_quota_exhausted,
        "gemini_request_timed_out" to R.string.gemini_request_timed_out,
        "http_error_rate_limited" to R.string.http_error_rate_limited,
        "http_error_server" to R.string.http_error_server,
        "http_error_unauthorized" to R.string.http_error_unauthorized,
        "http_error_unexpected" to R.string.http_error_unexpected,
        "job_cancelled_error" to R.string.job_cancelled_error,
        "main_waiting_for_audios" to R.string.main_waiting_for_audios,
        "microsoft_speech_audio_too_large" to R.string.microsoft_speech_audio_too_large,
        "microsoft_speech_audio_too_long" to R.string.microsoft_speech_audio_too_long,
        "microsoft_speech_configuration_cleared" to R.string.microsoft_speech_configuration_cleared,
        "microsoft_speech_endpoint_base_only" to R.string.microsoft_speech_endpoint_base_only,
        "microsoft_speech_endpoint_credentials" to R.string.microsoft_speech_endpoint_credentials,
        "microsoft_speech_endpoint_https" to R.string.microsoft_speech_endpoint_https,
        "microsoft_speech_endpoint_invalid" to R.string.microsoft_speech_endpoint_invalid,
        "microsoft_speech_endpoint_not_azure" to R.string.microsoft_speech_endpoint_not_azure,
        "microsoft_speech_endpoint_not_found" to R.string.microsoft_speech_endpoint_not_found,
        "microsoft_speech_endpoint_required" to R.string.microsoft_speech_endpoint_required,
        "microsoft_speech_endpoint_required_first" to R.string.microsoft_speech_endpoint_required_first,
        "microsoft_speech_endpoint_unreachable" to R.string.microsoft_speech_endpoint_unreachable,
        "microsoft_speech_key_invalid" to R.string.microsoft_speech_key_invalid,
        "microsoft_speech_key_missing" to R.string.microsoft_speech_key_missing,
        "microsoft_speech_region_invalid" to R.string.microsoft_speech_region_invalid,
        "microsoft_speech_region_unreachable" to R.string.microsoft_speech_region_unreachable,
        "microsoft_speech_region_validation_failed_http" to R.string.microsoft_speech_region_validation_failed_http,
        "microsoft_speech_request_rejected" to R.string.microsoft_speech_request_rejected,
        "microsoft_speech_resource_exhausted" to R.string.microsoft_speech_resource_exhausted,
        "microsoft_speech_unauthorized" to R.string.microsoft_speech_unauthorized,
        "provider_file_failed" to R.string.provider_file_failed,
        "provider_name_microsoft_speech" to R.string.provider_name_microsoft_speech,
        "provider_option_microsoft_speech" to R.string.provider_option_microsoft_speech,
        "provider_request_failed" to R.string.provider_request_failed,
        "provider_response_blocked" to R.string.provider_response_blocked,
        "provider_response_invalid" to R.string.provider_response_invalid,
        "provider_response_truncated" to R.string.provider_response_truncated,
        "queue_capacity_exceeded" to R.string.queue_capacity_exceeded,
        "queued_audio_unavailable" to R.string.queued_audio_unavailable,
        "share_batch_too_large" to R.string.share_batch_too_large,
        "share_file_empty" to R.string.share_file_empty,
        "share_file_too_large" to R.string.share_file_too_large,
        "share_insufficient_storage" to R.string.share_insufficient_storage,
        "share_read_failed" to R.string.share_read_failed,
        "share_too_many_files" to R.string.share_too_many_files,
        "share_unsupported_audio" to R.string.share_unsupported_audio,
        "stored_message_unavailable" to R.string.stored_message_unavailable,
        "summarization_status_cancelled" to R.string.summarization_status_cancelled,
        "summarization_status_error" to R.string.summarization_status_error,
        "summarization_status_generated" to R.string.summarization_status_generated,
        "summarization_status_nothing_to_stop" to R.string.summarization_status_nothing_to_stop,
        "summarization_status_nothing_to_summarize" to R.string.summarization_status_nothing_to_summarize,
        "summarizer_model_not_specified" to R.string.summarizer_model_not_specified,
        "transcription_audio_access_denied" to R.string.transcription_audio_access_denied,
        "transcription_audio_processing_failed" to R.string.transcription_audio_processing_failed,
        "transcription_failed_unexpected" to R.string.transcription_failed_unexpected,
        "transcription_foreground_denied_reopen" to R.string.transcription_foreground_denied_reopen,
        "transcription_foreground_denied_switching_backend" to
            R.string.transcription_foreground_denied_switching_backend,
        "transcription_notification_converting" to R.string.transcription_notification_converting,
        "transcription_notification_copying" to R.string.transcription_notification_copying,
        "transcription_notification_processing" to R.string.transcription_notification_processing,
        "transcription_notification_summarizing" to R.string.transcription_notification_summarizing,
        "transcription_provider_gemini" to R.string.transcription_provider_gemini,
        "transcription_retries_exhausted" to R.string.transcription_retries_exhausted,
        "transcription_retry_notification_body" to R.string.transcription_retry_notification_body,
        "transcription_status_conversion_for_chunking" to R.string.transcription_status_conversion_for_chunking,
        "transcription_status_conversion_required" to R.string.transcription_status_conversion_required,
        "transcription_status_converted_ready_gemini" to R.string.transcription_status_converted_ready_gemini,
        "transcription_status_converted_ready_microsoft" to R.string.transcription_status_converted_ready_microsoft,
        "transcription_status_converting_progress" to R.string.transcription_status_converting_progress,
        "transcription_status_copying_supported_gemini" to R.string.transcription_status_copying_supported_gemini,
        "transcription_status_copying_supported_microsoft" to R.string.transcription_status_copying_supported_microsoft,
        "transcription_status_diarization_fallback" to R.string.transcription_status_diarization_fallback,
        "transcription_status_part_message" to R.string.transcription_status_part_message,
        "transcription_status_preparing_local_gemini" to R.string.transcription_status_preparing_local_gemini,
        "transcription_status_preparing_local_microsoft" to R.string.transcription_status_preparing_local_microsoft,
        "transcription_status_preparing_upload_gemini" to R.string.transcription_status_preparing_upload_gemini,
        "transcription_status_preparing_upload_microsoft" to R.string.transcription_status_preparing_upload_microsoft,
        "transcription_status_provider_message" to R.string.transcription_status_provider_message,
        "transcription_status_split_into_parts" to R.string.transcription_status_split_into_parts,
        "transcription_status_starting" to R.string.transcription_status_starting,
        "transcription_status_unknown_format_suffix" to R.string.transcription_status_unknown_format_suffix,
        "transcription_status_uploading_gemini" to R.string.transcription_status_uploading_gemini,
        "transcription_status_uploading_microsoft" to R.string.transcription_status_uploading_microsoft,
        "transcription_storage_failed" to R.string.transcription_storage_failed,
        "validation_failed" to R.string.validation_failed,
        "validation_failed_http" to R.string.validation_failed_http,
        "validation_network_failed" to R.string.validation_network_failed,
        "verbatim_text" to R.string.verbatim_text,
    )

    private val plurals: Map<String, Int> = mapOf(
        "duration_seconds" to R.plurals.duration_seconds,
        "main_queued_files" to R.plurals.main_queued_files,
        "main_queuing_files" to R.plurals.main_queuing_files,
        "share_audio_file_count" to R.plurals.share_audio_file_count,
        "transcription_attempt_count" to R.plurals.transcription_attempt_count,
        "transcription_part_count" to R.plurals.transcription_part_count,
        "unfinished_job_count" to R.plurals.unfinished_job_count,
    )

    private val stringCodes: Map<Int, String> = strings.entries.associate { (code, id) -> id to code }
    private val pluralCodes: Map<Int, String> = plurals.entries.associate { (code, id) -> id to code }

    fun stringCode(id: Int): String? = stringCodes[id]

    fun stringId(code: String): Int? = strings[code]

    fun pluralCode(id: Int): String? = pluralCodes[id]

    fun pluralId(code: String): Int? = plurals[code]

    internal val registeredStrings: Map<String, Int> get() = strings

    internal val registeredPlurals: Map<String, Int> get() = plurals
}
