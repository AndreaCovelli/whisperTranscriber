package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.IOException

/**
 * App-written, user-facing text kept as a string resource until a [Context] is available. Pure logic
 * stays JVM-testable, and only text the app wrote itself can reach the UI or stored job errors.
 * Arguments that are themselves [UserMessage]s or [QuantityText]s are resolved recursively.
 */
data class UserMessage(@param:StringRes val id: Int, val args: List<Any> = emptyList()) {
    @Suppress("SpreadOperator") // Format arguments are a handful of values; the copy is negligible.
    fun resolve(context: Context): String = if (args.isEmpty()) {
        context.getString(id)
    } else {
        context.getString(id, *args.map { arg -> resolveArgument(context, arg) }.toTypedArray())
    }

    companion object {
        /** Text shown as is: stored text that predates encoded messages, or a lone [QuantityText]. */
        fun verbatim(text: Any): UserMessage = UserMessage(R.string.verbatim_text, listOf(text))
    }
}

/** A plural resource formatted with its own [quantity], for use as a [UserMessage] argument. */
data class QuantityText(@param:PluralsRes val id: Int, val quantity: Int)

private fun resolveArgument(context: Context, arg: Any): Any = when (arg) {
    is UserMessage -> arg.resolve(context)
    is QuantityText -> context.resources.getQuantityString(arg.id, arg.quantity, arg.quantity)
    else -> arg
}

/** Marks exceptions whose [userMessage] is safe and meaningful to show to the user. */
internal interface UserFacingException {
    val userMessage: UserMessage
}

internal class UserFacingIOException(override val userMessage: UserMessage, cause: Throwable? = null) :
    IOException(USER_FACING_EXCEPTION_MESSAGE, cause),
    UserFacingException

internal class UserFacingIllegalArgumentException(override val userMessage: UserMessage) :
    IllegalArgumentException(USER_FACING_EXCEPTION_MESSAGE),
    UserFacingException

internal open class UserFacingIllegalStateException(override val userMessage: UserMessage, cause: Throwable? = null) :
    IllegalStateException(USER_FACING_EXCEPTION_MESSAGE, cause),
    UserFacingException

internal inline fun requireUserFacing(condition: Boolean, lazyMessage: () -> UserMessage) {
    if (!condition) throw UserFacingIllegalArgumentException(lazyMessage())
}

internal fun Throwable.userMessageOr(@StringRes fallback: Int): UserMessage =
    (this as? UserFacingException)?.userMessage ?: UserMessage(fallback)

private const val USER_FACING_EXCEPTION_MESSAGE = "User-facing error"
