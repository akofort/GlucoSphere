package com.example.diabai.data

import kotlinx.serialization.Serializable

/** Which backend answers chat/report requests -- resolved dynamically per request by
 * [com.example.diabai.domain.llm.LLMProviderManager], so the rest of the app (chat UI,
 * Übersicht tab, MCP tool loop) stays unaware of which one is actually active. */
@Serializable
enum class LlmProviderType(val label: String) {
    LOCAL("Lokales Modell (LiteRT / Gemma)"),
    GEMINI("Google Gemini API"),
    CLAUDE("Anthropic Claude API"),
    OPENAI("OpenAI API / OpenRouter"),
    DEEPSEEK("DeepSeek API"),

    /** Item 3: a decoupled, always-available option (not nested inside [CLAUDE]'s own Base-URL
     * preset) that speaks the same Anthropic-Messages wire format as [CLAUDE] via
     * [com.example.diabai.domain.llm.ClaudeApiProvider], pointed at [ONEPROVIDER_GATEWAY_BASE_URL]
     * either way. Two modes, both under this one enum entry (the label is generic on purpose): an
     * own key ([AppSettings.oneProviderApiKey] non-blank) behaves like any other cloud provider,
     * no daily limit; left blank, it falls back to the app-embedded key, gated behind
     * [ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT] (see [com.example.diabai.domain.llm.LLMProviderManager]/
     * [com.example.diabai.domain.DiabetesAgent]). */
    ONEPROVIDER_FREE("OneProvider (eigener Key oder kostenloses Freikontingent)"),
}

const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

/** Anthropic's own Messages API -- [com.example.diabai.domain.llm.ClaudeApiProvider]'s default,
 * overridable per "Anthropic-kompatibles Gateway" (e.g. OneProvider) the same way
 * [DEFAULT_OPENAI_BASE_URL] already is for OpenAI/OpenRouter/Ollama (see [AppSettings.claudeBaseUrl]). */
const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1"

/** Anthropic-compatible gateway used both as a Base-URL preset for [LlmProviderType.CLAUDE] (a
 * user's own OneProvider key) and, unconditionally, by [LlmProviderType.ONEPROVIDER_FREE] (the
 * app-embedded free-tier key) -- same host either way, only the key/quota handling differs. */
const val ONEPROVIDER_GATEWAY_BASE_URL = "https://api.oneprovider.dev/v1"

/** DeepSeek's API is OpenAI-Chat-Completions-compatible, so [DEEPSEEK] is served by the same
 * [com.example.diabai.domain.llm.OpenAiApiProvider] as OpenAI/OpenRouter, just pointed at this
 * fixed endpoint instead of a user-editable one -- see [com.example.diabai.domain.llm.LLMProviderManager]. */
const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
