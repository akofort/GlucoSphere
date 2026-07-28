package com.example.diabai.domain.llm

import com.example.diabai.domain.EngineState
import com.example.diabai.domain.LiteRtInferenceEngine
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wraps the on-device Gemma model (via [LiteRtInferenceEngine]/LiteRT-LM) behind the
 * provider-agnostic [LLMProvider] contract, so [com.example.diabai.domain.DiabetesAgent] can
 * treat it exactly like a cloud provider. */
class LiteRTProvider(private val engine: LiteRtInferenceEngine) : LLMProvider {
    override val isReady: Boolean
        get() = engine.state.value is EngineState.Ready

    override val notReadyReason: String = "Kein lokales Modell geladen"

    override fun startConversation(systemPrompt: String, tools: List<LlmToolSpec>): LlmConversation? {
        val conversation = engine.createConversation(systemPrompt, tools.map { LiteRtOpenApiTool(it) })
            ?: return null
        return LiteRtLlmConversation(conversation)
    }

    override suspend fun testConnection(): Result<Unit> =
        if (isReady) Result.success(Unit) else Result.failure(IllegalStateException(notReadyReason))
}

private class LiteRtOpenApiTool(private val spec: LlmToolSpec) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String {
        val schema = buildJsonObject {
            put("name", spec.name)
            spec.description?.let { put("description", it) }
            put("parameters", spec.parametersSchema)
        }
        return schema.toString()
    }

    // Never invoked -- conversations run with `automaticToolCalling = false` so DiabetesAgent
    // gates every call behind a user confirmation and dispatches it via McpServerPool itself.
    override fun execute(paramsJsonString: String): String = "{}"
}

private class LiteRtLlmConversation(private val conversation: Conversation) : LlmConversation {
    override fun sendUserMessage(text: String): Flow<LlmStreamEvent> =
        conversation.sendMessageAsync(text).toLlmEvents()

    /** LiteRT-LM's `Message.toolCalls` is itself a list (see [toLlmEvents]), so its wire format
     * already accepts multiple [Content.ToolResponse] entries in one tool message -- all results
     * from a parallel batch are answered together in one call, same as every other provider. */
    override fun sendToolResults(results: List<ToolResultPayload>): Flow<LlmStreamEvent> {
        val message = Message.tool(Contents.of(results.map { Content.ToolResponse(it.toolName, it.resultText) }))
        return conversation.sendMessageAsync(message).toLlmEvents()
    }

    override fun close() = conversation.close()

    // Only the first tool call per round is handled -- keeps the confirmation UX to one
    // decision at a time, matching the other providers' single-tool-call-per-round contract.
    private fun Flow<Message>.toLlmEvents(): Flow<LlmStreamEvent> = flow {
        var lastMessage: Message? = null
        collect { message ->
            lastMessage = message
            val text = message.extractText()
            if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
        }
        val toolCalls = lastMessage?.toolCalls.orEmpty()
        if (toolCalls.isEmpty()) return@flow
        // LiteRT-LM's own tool calls carry no separate id -- like Gemini, the tool name itself
        // doubles as the correlation id (see LLMProvider.kt's RequestedToolCall doc comment).
        val requested = toolCalls.map { toolCall ->
            val args = buildJsonObject {
                toolCall.arguments.forEach { (key, value) -> put(key, anyToJsonElement(value)) }
            }
            RequestedToolCall(id = toolCall.name, name = toolCall.name, arguments = args)
        }
        emit(LlmStreamEvent.ToolCallRequested(requested))
    }

    /** LiteRT-LM's [Message] has no plain-text accessor; text lives among its [Content] items. */
    private fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, v) -> if (key is String) put(key, anyToJsonElement(v)) }
        }
        is List<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }
}
