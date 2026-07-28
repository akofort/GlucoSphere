package com.example.diabai.domain.llm

import com.example.diabai.data.LlmProviderType

/** Which kind of request a model is being resolved for -- lets "Automatisch" mode pick a
 * different model per purpose instead of one fixed choice for everything. */
enum class LlmPurpose {
    /** Interactive chat: latency matters more than depth, so "Automatisch" picks the provider's
     * fast/light model. */
    CHAT,

    /** Übersicht-tab metrics/narrative and report generation: runs unattended and only a few
     * times per session, so "Automatisch" picks the provider's most capable model instead. */
    ANALYSIS,
}

const val AUTO_MODEL_ID = "auto"

/** [priceTier] is a rough, human-readable relative-cost indicator (more "€" = more expensive per
 * token vs. this same provider's other model) -- not a real price, just enough for a user to
 * guess "this one costs more to run" before picking it. */
data class ModelOption(val id: String, val label: String, val priceTier: String)

/** `provider/model-name`, `model-name-v1`, or an OpenRouter-style `provider/model:tag` suffix --
 * no whitespace anywhere. Used to validate "Benutzerdefiniertes Modell eingeben" before it can be
 * tested/saved (see LlmConfigScreen), so an obviously-malformed id never even reaches the API. */
val CUSTOM_MODEL_ID_REGEX = Regex("""^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?(:[A-Za-z0-9._-]+)?$""")

/** Per-provider model choices shown in the LLM-Konfiguration dropdown, each list ordered
 * fast/light model first, most capable model last -- [resolve] relies on that order to implement
 * "Automatisch" mode's per-[LlmPurpose] pick. Model IDs are plain strings (not hardcoded per
 * provider file anymore) so a provider class only ever sees "whichever id resolve() decided on".
 *
 * A selection that isn't [AUTO_MODEL_ID] and isn't in this catalog is treated as a manually
 * entered custom id (see "Benutzerdefiniertes Modell eingeben" in LlmConfigScreen) and passed
 * straight through by [resolve] rather than silently replaced.
 */
object ModelCatalog {
    // Narrowed to exactly the two models explicitly requested -- both confirmed current/live via
    // ai.google.dev/gemini-api/docs/models (checked 2026-07-25): "gemini-3.5-flash-lite" ("fastest,
    // most cost-effective 3.5 model") and "gemini-3.6-flash" (filed under the docs' own "Flagship/
    // Advanced" tier despite the "flash" name). [resolve] auto-picks the FIRST for LlmPurpose.CHAT
    // and the LAST for LlmPurpose.ANALYSIS, so this ordering puts Flash Lite on chat and 3.6 Flash
    // on Übersicht/report generation. The earlier "gemini-2.0-pro-exp" flagship entry (CONFIRMED
    // shut down -- the actual root cause of a previously reported "Google zeigt immer offline an")
    // and "gemini-2.5-flash"/"gemini-2.5-pro" are dropped entirely per this explicit narrowing.
    private val GEMINI = listOf(
        ModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite (schnell, Standard)", "€"),
        ModelOption("gemini-3.6-flash", "Gemini 3.6 Flash (Flaggschiff)", "€€"),
    )
    private val CLAUDE = listOf(
        ModelOption("claude-3-5-haiku-latest", "Claude 3.5 Haiku (schnell)", "€"),
        ModelOption("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet (Allrounder)", "€€"),
        ModelOption("claude-3-opus-latest", "Claude 3 Opus (Tiefenanalyse)", "€€€"),
    )

    // Narrowed to exactly 3 per the user's request, "deepseek/deepseek-v4-flash" first/default.
    // NOTE (kept from earlier passes): "deepseek/deepseek-v4-flash" and "openrouter/auto-beta"
    // don't match any OpenRouter-published model id as of this writing -- OpenRouter's real
    // dynamic-routing id is "openrouter/auto" (no "-beta"). The user has now explicitly
    // reconfirmed "deepseek-v4-flash" as the required model id (with an exact request-payload
    // spec) across three separate requests, so it's kept exactly as specified rather than
    // silently substituted -- verify against https://openrouter.ai/models before relying on it;
    // a wrong id fails cleanly with an API error, not a crash.
    private val OPENAI = listOf(
        ModelOption("deepseek/deepseek-v4-flash", "DeepSeek v4 Flash (schnell, Standard)", "€"),
        ModelOption("google/gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", "€"),
        ModelOption("openrouter/auto-beta", "OpenRouter Auto (dynamisch)", "€"),
    )

    // DeepSeek's own API (not via OpenRouter) -- confirmed via api-docs.deepseek.com (checked
    // 2026-07-25): the current, real model ids are exactly "deepseek-v4-flash" and
    // "deepseek-v4-pro". The legacy aliases this catalog used to fall back to ("deepseek-chat"/
    // "deepseek-reasoner") were discontinued 2026-07-24 -- yesterday as of this writing -- so
    // keeping either would now fail outright. "DeepSeek-R1" is deliberately NOT listed here: R1
    // predates the V4 generation and isn't confirmed as a still-valid model id on DeepSeek's OWN
    // endpoint (unlike via OpenRouter, where it's a distinct, still-served model) -- a user who
    // specifically wants R1 direct can enter it via "Benutzerdefiniertes Modell eingeben" (which
    // has its own "Testen" gate, so a wrong/retired id fails cleanly instead of silently). Two
    // entries only: [resolve] auto-picks the FIRST for LlmPurpose.CHAT, the LAST for
    // LlmPurpose.ANALYSIS -- so "Automatisch" now correctly lands on V4 Flash for chat and V4 Pro
    // for Übersicht/report generation, never the old (now-dead) reasoner alias.
    private val DEEPSEEK = listOf(
        ModelOption("deepseek-v4-flash", "DeepSeek V4 Flash (schnell, Standard)", "€"),
        ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro (Flaggschiff, Tiefenanalyse)", "€€"),
    )

    // OneProvider's gateway speaks the Anthropic-Messages wire format (same shape [CLAUDE] speaks,
    // see ONEPROVIDER_GATEWAY_BASE_URL) and, per its own live error response to an unsupported
    // model id (a 400 "model \"gpt-5.6-luna\" is not available; supported: claude-fable-5,
    // claude-haiku-4-5-20251001, claude-opus-4-6, claude-opus-4-7, claude-opus-4-8, claude-opus-5,
    // claude-sonnet-4-6, claude-sonnet-5" -- confirmed 2026-07-26, NOT the "claude-haiku-4-5-20251001
    // or gpt-5.6-luna" originally assumed), is Claude-only -- no GPT-family model is actually
    // reachable through it. [resolve] auto-picks the FIRST for LlmPurpose.CHAT, the LAST for
    // LlmPurpose.ANALYSIS, same as every other catalog here.
    private val ONEPROVIDER = listOf(
        ModelOption("claude-haiku-4-5-20251001", "Claude Haiku 4.5 (schnell, Standard)", "€"),
        ModelOption("claude-opus-5", "Claude Opus 5 (Flaggschiff)", "€€€"),
    )

    fun optionsFor(type: LlmProviderType): List<ModelOption> = when (type) {
        LlmProviderType.GEMINI -> GEMINI
        LlmProviderType.CLAUDE -> CLAUDE
        LlmProviderType.OPENAI -> OPENAI
        LlmProviderType.DEEPSEEK -> DEEPSEEK
        LlmProviderType.ONEPROVIDER_FREE -> ONEPROVIDER
        LlmProviderType.LOCAL -> emptyList()
    }

    /** [selection] is [AUTO_MODEL_ID], one of [optionsFor]'s ids, or a manually entered custom
     * id. An explicit selection always wins regardless of [purpose]; "Automatisch" resolves to
     * the fast model for [LlmPurpose.CHAT] and the flagship for [LlmPurpose.ANALYSIS] -- falling
     * back to [selection] itself if the provider has no catalog (e.g. [LlmProviderType.LOCAL]). */
    fun resolve(type: LlmProviderType, selection: String, purpose: LlmPurpose): String {
        if (selection != AUTO_MODEL_ID) return selection
        val options = optionsFor(type)
        return when (purpose) {
            LlmPurpose.CHAT -> options.firstOrNull()?.id ?: selection
            LlmPurpose.ANALYSIS -> options.lastOrNull()?.id ?: selection
        }
    }
}
