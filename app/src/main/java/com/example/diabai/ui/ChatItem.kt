package com.example.diabai.ui

import com.example.diabai.domain.ConfirmationRequest

sealed interface ChatItem {
    val id: String

    /** Wall-clock time this item was created -- defaults to "now" at construction, which is
     * correct for every live call site (this only ever runs on the coroutine actually producing
     * the item in real time). Used for the "kompletter Chatverlauf mit ... Zeitstempeln" export
     * (see [buildChatShareText]) -- a message restored from persisted chat history (see
     * [com.example.diabai.ui.GlucoSphereViewModel]'s session-load path) has no real original timestamp
     * to recover, so it's stamped with the load time instead; that's a known, documented
     * limitation of exporting a *reloaded* old session rather than the live one. */
    val timestampMillis: Long

    data class UserMessage(
        override val id: String,
        val text: String,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val markdown: String,
        val isStreaming: Boolean,
        /** Wall-clock time from sending the request to fully receiving this answer (set once,
         * on [com.example.diabai.domain.AgentEvent.Complete]) -- null while still streaming or
         * for a message restored from persisted chat history, where the original timing is gone. */
        val durationMillis: Long? = null,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    /** A short status line for a tool call being started/declined/completed -- this is what makes
     * "eingeschaltete Tools" (which tools actually ran during the conversation, not just which
     * were toggled on) recoverable in the share export, see [buildChatShareText]. */
    data class ToolActivity(
        override val id: String,
        val toolName: String,
        val summary: String,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class PendingConfirmation(
        override val id: String,
        val description: String,
        val request: ConfirmationRequest,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class ErrorMessage(
        override val id: String,
        val text: String,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    /** A small, non-bubble informational line -- currently only "Auto-routed zu: {model}" for
     * OpenRouter's dynamic-routing "auto" model (see [com.example.diabai.domain.AgentEvent.ModelResolved]). */
    data class SystemNote(
        override val id: String,
        val text: String,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : ChatItem
}

/** [AssistantMessage.markdown] split into an optional hidden-by-default reasoning block and the
 * actual answer -- reasoning models (Qwen3, DeepSeek, ...) often wrap their chain-of-thought in
 * `<think>...</think>` (or, for providers with a dedicated reasoning field, whatever gets folded
 * into the same markdown string upstream) ahead of the real answer. Re-parsed from the full
 * accumulated text on every recomposition rather than incrementally during streaming -- simpler,
 * and cheap enough for realistic message lengths -- so a `<think>` tag split across two separate
 * streamed deltas is still found correctly once both have arrived. */
data class ParsedAssistantContent(val reasoning: String?, val reasoningTokenEstimate: Int, val answer: String)

private val thinkTagRegex = Regex("(?s)<think>(.*?)</think>")

fun String.splitReasoning(): ParsedAssistantContent {
    val match = thinkTagRegex.find(this) ?: return ParsedAssistantContent(null, 0, this)
    val reasoning = match.groupValues[1].trim()
    val answer = (substring(0, match.range.first) + substring(match.range.last + 1)).trim()
    // No real tokenizer available client-side -- ~4 characters per token is the usual rough
    // heuristic for English/German text, good enough for a "(~N Tokens)" hint, not billing.
    val tokenEstimate = (reasoning.length / 4).coerceAtLeast(1)
    return ParsedAssistantContent(reasoning, tokenEstimate, answer)
}
