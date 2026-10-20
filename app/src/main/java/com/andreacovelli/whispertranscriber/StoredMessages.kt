package com.andreacovelli.whispertranscriber

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Stores a [UserMessage] as JSON that names the string by a stable code, so stored job text is
 * shown in the language the app is using when it is read, not when it was written.
 *
 * Format: `{"k":"code","a":[arg, ...]}`, where each argument is `{"s":text}`, `{"i":integer}`,
 * `{"f":decimal}`, `{"m":message}`, or `{"p":"plural code","q":quantity}`. Text that was stored before
 * messages were encoded is kept as `{"t":text}` and shown as is.
 */
internal object StoredMessages {
    fun encode(message: UserMessage): String = encodeMessage(message).toString()

    /** Wraps text that must be shown as is, such as job text stored before messages were encoded. */
    fun encodeLiteral(text: String): String = JSONObject().put(LITERAL, text).toString()

    /** Returns null for null input. Text that is not an encoded message is shown as is. */
    fun decode(stored: String?): UserMessage? {
        if (stored == null) return null
        return try {
            decodeMessage(JSONObject(stored))
        } catch (_: JSONException) {
            UserMessage.verbatim(stored)
        }
    }

    private fun encodeMessage(message: UserMessage): JSONObject {
        if (message.id == R.string.verbatim_text && message.args.singleOrNull() is String) {
            return JSONObject().put(LITERAL, message.args.single())
        }
        // StoredMessageRegistryTest keeps every app message registered; this only guards a missed one.
        val code = StoredMessageRegistry.stringCode(message.id) ?: StoredMessageRegistry.UNAVAILABLE_CODE
        val json = JSONObject().put(KEY, code)
        if (message.args.isNotEmpty()) {
            json.put(ARGS, JSONArray(message.args.map(::encodeArgument)))
        }
        return json
    }

    private fun encodeArgument(arg: Any): JSONObject = when (arg) {
        is UserMessage -> JSONObject().put(MESSAGE, encodeMessage(arg))
        is QuantityText -> StoredMessageRegistry.pluralCode(arg.id)
            ?.let { code -> JSONObject().put(PLURAL, code).put(QUANTITY, arg.quantity) }
            ?: JSONObject().put(INTEGER, arg.quantity.toLong())
        is Byte, is Short, is Int, is Long -> JSONObject().put(INTEGER, (arg as Number).toLong())
        is Float, is Double -> JSONObject().put(DECIMAL, (arg as Number).toDouble())
        else -> JSONObject().put(STRING, arg.toString())
    }

    private fun decodeMessage(json: JSONObject): UserMessage {
        if (json.has(LITERAL)) return UserMessage.verbatim(json.getString(LITERAL))
        val id = StoredMessageRegistry.stringId(json.getString(KEY)) ?: R.string.stored_message_unavailable
        val args = json.optJSONArray(ARGS) ?: return UserMessage(id)
        return UserMessage(id, List(args.length()) { index -> decodeArgument(args.getJSONObject(index)) })
    }

    private fun decodeArgument(json: JSONObject): Any = when {
        json.has(MESSAGE) -> decodeMessage(json.getJSONObject(MESSAGE))
        json.has(PLURAL) -> {
            val quantity = json.getInt(QUANTITY)
            StoredMessageRegistry.pluralId(json.getString(PLURAL))?.let { id -> QuantityText(id, quantity) }
                ?: quantity
        }
        json.has(INTEGER) -> json.getLong(INTEGER).let { value ->
            if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
        }
        json.has(DECIMAL) -> json.getDouble(DECIMAL)
        else -> json.getString(STRING)
    }

    private const val KEY = "k"
    private const val ARGS = "a"
    private const val LITERAL = "t"
    private const val STRING = "s"
    private const val INTEGER = "i"
    private const val DECIMAL = "f"
    private const val MESSAGE = "m"
    private const val PLURAL = "p"
    private const val QUANTITY = "q"
}
