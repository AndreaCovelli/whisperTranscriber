package com.andreacovelli.whispertranscriber.history

private val HISTORY_SEARCH_TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

internal fun extractHistorySearchTokens(rawQuery: String): List<String> = HISTORY_SEARCH_TOKEN_REGEX
    .findAll(rawQuery)
    .map { it.value }
    .distinct()
    .toList()

internal fun buildHistorySearchQuery(rawQuery: String): String =
    extractHistorySearchTokens(rawQuery).joinToString(" ") { "$it*" }
