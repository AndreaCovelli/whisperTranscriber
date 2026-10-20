package com.andreacovelli.whispertranscriber

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredMessagesTest {
    @Test
    fun everyMessageCreatedInTheApp_hasAStoredCode() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("main sources not found from ${File("").absolutePath}", sources.isNotEmpty())
        val names = sources.flatMap { source -> MESSAGE_PATTERN.findAll(source.readText()).toList() }
            .map { match -> match.groupValues[1] to match.groupValues[2] }
            .toSet()
        assertTrue(names.isNotEmpty())

        val missing = names.filter { (type, name) ->
            val registered = if (type == "string") {
                StoredMessageRegistry.registeredStrings
            } else {
                StoredMessageRegistry.registeredPlurals
            }
            name !in registered
        }
        assertEquals("Register these messages in StoredMessageRegistry", emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun registeredCodes_nameDistinctResources() {
        val strings = StoredMessageRegistry.registeredStrings
        val plurals = StoredMessageRegistry.registeredPlurals

        assertEquals(strings.size, strings.values.toSet().size)
        assertEquals(plurals.size, plurals.values.toSet().size)
        assertEquals(R.string.stored_message_unavailable, strings[StoredMessageRegistry.UNAVAILABLE_CODE])
    }

    @Test
    fun nestedMessagesAndArguments_roundTrip() {
        val message = UserMessage(
            R.string.transcription_status_provider_message,
            listOf(
                "Gemini",
                UserMessage(
                    R.string.queue_capacity_exceeded,
                    listOf(QuantityText(R.plurals.unfinished_job_count, 50)),
                ),
            ),
        )

        assertEquals(message, StoredMessages.decode(StoredMessages.encode(message)))
    }

    @Test
    fun numbers_keepTheirKind() {
        val message = UserMessage(R.string.transcription_status_converting_progress, listOf(42, 1.5, 5_000_000_000L))

        assertEquals(message, StoredMessages.decode(StoredMessages.encode(message)))
    }

    @Test
    fun verbatimText_isStoredAsALiteral() {
        val message = UserMessage.verbatim("Provider said no")

        assertEquals("""{"t":"Provider said no"}""", StoredMessages.encode(message))
        assertEquals(message, StoredMessages.decode(StoredMessages.encode(message)))
        assertEquals(message, StoredMessages.decode(StoredMessages.encodeLiteral("Provider said no")))
    }

    @Test
    fun textThatIsNotAnEncodedMessage_isShownAsIs() {
        assertEquals(UserMessage.verbatim("Uploading audio…"), StoredMessages.decode("Uploading audio…"))
        assertNull(StoredMessages.decode(null))
    }

    @Test
    fun unknownCodes_decodeToTheUnavailableMessage() {
        assertEquals(
            UserMessage(R.string.stored_message_unavailable),
            StoredMessages.decode("""{"k":"removed_string"}"""),
        )
        assertEquals(
            UserMessage(R.string.queue_capacity_exceeded, listOf(7)),
            StoredMessages.decode("""{"k":"queue_capacity_exceeded","a":[{"p":"removed_plural","q":7}]}"""),
        )
    }

    private companion object {
        val MESSAGE_PATTERN = Regex(
            """(?:UserMessage|QuantityText|userMessageOr|ProviderResponseException)\(\s*R\.(string|plurals)\.(\w+)""",
        )
    }
}
