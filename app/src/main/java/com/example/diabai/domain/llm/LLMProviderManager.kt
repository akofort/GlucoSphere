package com.example.diabai.domain.llm

import com.example.diabai.BuildConfig
import com.example.diabai.data.AppSettings
import com.example.diabai.data.DEFAULT_DEEPSEEK_BASE_URL
import com.example.diabai.data.DEFAULT_OPENAI_BASE_URL
import com.example.diabai.data.LlmProviderType
import com.example.diabai.data.ONEPROVIDER_GATEWAY_BASE_URL
import com.example.diabai.domain.LiteRtInferenceEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * Resolves which [LLMProvider] backs chat/report generation right now, based on the user's
 * saved [AppSettings.llmProviderType]. Everything downstream ([com.example.diabai.domain.DiabetesAgent],
 * the chat UI, the Übersicht tab's AI tips) is unaware of which one is actually active.
 */
class LLMProviderManager(private val liteRtEngine: LiteRtInferenceEngine) {
    // Cloud generation calls can legitimately run long (multi-round tool calling, slower
    // models, large tool results for multi-day CGM history) -- a generous timeout avoids
    // spurious failures mid-stream rather than a snappier one that fights real usage.
    //
    // requestTimeoutMillis alone is NOT enough on the OkHttp engine: Ktor's HttpTimeout plugin
    // only overrides OkHttp's own built-in connect/read/write timeouts (10s each, hardcoded in
    // OkHttp itself) when connectTimeoutMillis/socketTimeoutMillis are *also* set explicitly.
    // Left unset (as this was), any gap longer than ~10s between two response chunks -- an
    // OpenRouter "auto" routing decision, a slow multi-round tool-calling turn, a model that's
    // slow to produce its first streamed token -- trips OkHttp's short default read timeout
    // instead of the intended 180s budget, surfacing as "Socket timeout has expired [...,
    // socket_timeout=unknown] ms" (the "unknown" is Ktor reporting a limit *it* was never told,
    // i.e. exactly this gap).
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
    }

    private val liteRtProvider = LiteRTProvider(liteRtEngine)

    /** [purpose] only matters when the active provider's model selection is "Automatisch" (see
     * [ModelCatalog.resolve]) -- an explicit model choice always wins regardless of purpose. */
    fun resolve(settings: AppSettings, purpose: LlmPurpose = LlmPurpose.ANALYSIS): LLMProvider = when (settings.llmProviderType) {
        LlmProviderType.LOCAL -> liteRtProvider
        LlmProviderType.GEMINI -> GeminiApiProvider(
            httpClient, settings.geminiApiKey, ModelCatalog.resolve(LlmProviderType.GEMINI, settings.geminiModel, purpose),
        )
        LlmProviderType.CLAUDE -> ClaudeApiProvider(
            httpClient, settings.claudeApiKey, settings.claudeBaseUrl,
            ModelCatalog.resolve(LlmProviderType.CLAUDE, settings.claudeModel, purpose),
        )
        LlmProviderType.OPENAI -> OpenAiApiProvider(
            httpClient, settings.openAiApiKey, settings.openAiBaseUrl,
            ModelCatalog.resolve(LlmProviderType.OPENAI, settings.openAiModel, purpose),
        )
        // DeepSeek's API is OpenAI-Chat-Completions-compatible -- reuses OpenAiApiProvider
        // pointed at DeepSeek's fixed endpoint instead of a second near-identical provider class.
        LlmProviderType.DEEPSEEK -> OpenAiApiProvider(
            httpClient, settings.deepseekApiKey, DEFAULT_DEEPSEEK_BASE_URL,
            ModelCatalog.resolve(LlmProviderType.DEEPSEEK, settings.deepseekModel, purpose),
        )
        // Item 3: an own key (settings.oneProviderApiKey) behaves like any other cloud provider;
        // left blank, falls back to the app-embedded free-tier key (see BuildConfig, sourced from
        // local.properties, never committed). The daily 3-request cap for the embedded-key path is
        // enforced earlier, in DiabetesAgent -- by the time this resolves a provider instance, the
        // quota check already passed.
        LlmProviderType.ONEPROVIDER_FREE -> ClaudeApiProvider(
            httpClient,
            settings.oneProviderApiKey.ifBlank { BuildConfig.ONEPROVIDER_FREE_TIER_KEY },
            ONEPROVIDER_GATEWAY_BASE_URL,
            ModelCatalog.resolve(LlmProviderType.ONEPROVIDER_FREE, settings.oneProviderModel, purpose),
        )
    }

    /** Used by the "Key testen" button -- builds a throwaway provider instance from whatever is
     * currently typed into the settings form, without touching the persisted/active one. */
    suspend fun testKey(type: LlmProviderType, apiKey: String, baseUrl: String = DEFAULT_OPENAI_BASE_URL): Result<Unit> =
        when (type) {
            LlmProviderType.LOCAL -> liteRtProvider.testConnection()
            LlmProviderType.GEMINI -> GeminiApiProvider(httpClient, apiKey).testConnection()
            LlmProviderType.CLAUDE -> ClaudeApiProvider(httpClient, apiKey, baseUrl).testConnection()
            LlmProviderType.OPENAI -> OpenAiApiProvider(httpClient, apiKey, baseUrl).testConnection()
            LlmProviderType.DEEPSEEK -> OpenAiApiProvider(httpClient, apiKey, DEFAULT_DEEPSEEK_BASE_URL).testConnection()
            // [apiKey] here is whatever's currently typed into the (optional) form field -- blank
            // means "test the app-embedded free-tier key", exactly what a real request would fall
            // back to, see resolve() above.
            LlmProviderType.ONEPROVIDER_FREE ->
                ClaudeApiProvider(httpClient, apiKey.ifBlank { BuildConfig.ONEPROVIDER_FREE_TIER_KEY }, ONEPROVIDER_GATEWAY_BASE_URL).testConnection()
        }

    /** "Testen" for a manually entered "Benutzerdefiniertes Modell" -- unlike [testKey] (which
     * only proves the API key/endpoint is accepted), this actually starts a conversation and
     * sends one trivial message with the exact custom model id, so a key that's valid but a model
     * id that's misspelled/unpublished/wrong-shaped for this provider still surfaces as a clear
     * failure here instead of only failing later on a real chat/Übersicht turn. */
    suspend fun testCustomModel(type: LlmProviderType, apiKey: String, baseUrl: String, modelId: String): Result<Unit> = runCatching {
        require(modelId.isNotBlank()) { "Bitte eine Modell-ID eingeben" }
        val provider: LLMProvider = when (type) {
            LlmProviderType.LOCAL -> error("Lokales Modell unterstützt keine benutzerdefinierte Modell-ID")
            LlmProviderType.GEMINI -> GeminiApiProvider(httpClient, apiKey, modelId)
            LlmProviderType.CLAUDE -> ClaudeApiProvider(httpClient, apiKey, baseUrl, modelId)
            LlmProviderType.OPENAI -> OpenAiApiProvider(httpClient, apiKey, baseUrl, modelId)
            LlmProviderType.DEEPSEEK -> OpenAiApiProvider(httpClient, apiKey, DEFAULT_DEEPSEEK_BASE_URL, modelId)
            LlmProviderType.ONEPROVIDER_FREE ->
                ClaudeApiProvider(httpClient, apiKey.ifBlank { BuildConfig.ONEPROVIDER_FREE_TIER_KEY }, ONEPROVIDER_GATEWAY_BASE_URL, modelId)
        }
        val conversation = provider.startConversation("Antworte ausschließlich mit dem einen Wort \"OK\".", emptyList())
            ?: error(provider.notReadyReason)
        try {
            conversation.sendUserMessage("Ping").collect { }
        } finally {
            conversation.close()
        }
    }

    fun close() {
        httpClient.close()
    }
}
