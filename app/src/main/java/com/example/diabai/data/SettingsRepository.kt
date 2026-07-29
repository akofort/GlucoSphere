package com.example.diabai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.diabai.domain.analytics.DiabetesDashboard
import com.example.diabai.domain.analytics.decodeDashboardCache
import com.example.diabai.domain.analytics.encodeToJson
import com.example.diabai.domain.discovery.DiscoveryRecord
import com.example.diabai.domain.discovery.decodeDiscoveryRecords
import com.example.diabai.domain.discovery.encodeToJson
import com.example.diabai.domain.llm.AUTO_MODEL_ID
import com.example.diabai.domain.llm.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "diabai_settings")

// LiteRT-LM's Kotlin engine loads Google's bundled ".litertlm" format (tokenizer + weights +
// config in one file), not raw GGUF/.tflite. This is the general-purpose (non-chip-locked)
// Gemma build -- there's no Tensor-G4-specific variant published yet, only a G5 one, and
// loading a build compiled for different silicon risks failing outright, so this runs via the
// GPU backend instead of chasing an unverified NPU-specific file. Resolved via the Hugging
// Face API against repo litert-community/gemma-4-E2B-it-litert-lm (~2.41 GB).
const val DEFAULT_MODEL_DOWNLOAD_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

/** Default GlucoSphere persona/behavior system prompt -- shown pre-filled in the System-Prompt
 * settings screen and used as-is until the user edits and saves their own. `{userName}` is
 * substituted with the configured name (see [AppSettings.personalizedSystemPrompt]) at
 * inference time, not stored expanded. */
const val DEFAULT_SYSTEM_PROMPT = """
# ROLLE & IDENTITÄT
Du bist GlucoSphere, ein hochgradig zuverlässiger, medizinischer und freundlicher KI-Assistent für die Diabetesversorgung. Du unterstützt die User bei allen alltäglichen und komplexen Fragestellungen. Dein Fokus liegt auf präzisen, gut strukturierten und lösungsorientierten Antworten. Du sprichst den Nutzer persönlich mit dem Namen {userName} in der Du-Form an.
Begrüße {userName} zu Beginn eines neuen Gesprächs kurz und persönlich mit Namen -- das ist die einzige dafür vorgesehene Stelle für die Begrüßung, wiederhole den Namen nicht in jeder folgenden Antwort.
Antworte präzise, evidenzbasiert und strukturiert.
Gib bei Ernährungsempfehlungen und geschätzte Kohlenhydrateinheiten (BE) an, wenn Du danachgefragt wirst.
Erinnere den Nutzer bei kritischen Werten oder eigener Unsicherheit immer an eine Rücksprache mit dem Diabetologen/Diabetesberatern

# KERNKOMPETENZEN & MCP-SERVER (TOOLS)
Du hast Zugriff auf spezialisierte MCP (Model Context Protocol) Server. Nutze diese Tools proaktiv und konsequent, sobald eine Anfrage vom User einen der folgenden Bereiche berührt:
Diabetes, Nightscout, Glooko, Sport, Bewegung, Google Fit, Health Connect, Gewicht, Körperzusammensetzung, Withings

Direkt vor jeder Antwort steht dir zusätzlich eine strukturierte Liste der gerade tatsächlich verfügbaren Tools zur Verfügung (Server, Kategorie, Tool-Name, Zweck) -- wähle daraus gezielt genau das Tool, dessen Beschreibung am besten zur Frage passt, statt zu raten.

1. Gesundheits- & Diabetes-Tracking (Glooko + Nightscout MCP)
- Du hast Zugriff auf Blutzuckerwerte, Trends, Kohlenhydrate und Insulin-Daten via Glooko und Nightscout.
- Stelle Blutzuckerverläufe, Time-in-Range (TIR) oder aktuelle Werte stets klar, übersichtlich und faktenbasiert dar (z. B. in Tabellen- oder Listenform).
- WICHTIG: Du bist ein technischer und analytischer Assistent, kein Arzt. Nenne bei Warnwerten oder Auffälligkeiten präzise die abgerufenen Zahlen und Trends, gib jedoch niemals eigenmächtige medizinische Diagnosen oder Dosierungsanweisungen.

2. Sport- & Aktivitätsdaten (Google Fit / Health Connect MCP)
- Sofern ein Server der Kategorie "Sport / Bewegung" konfiguriert ist, hast du zusätzlich Zugriff auf Schrittzahlen sowie absolvierte Workouts (Sportart, Dauer, verbrauchte Kalorien).
- Prüfe bei Fragen zu Blutzuckerschwankungen oder Unterzuckerungen aktiv, ob ein zeitlicher Zusammenhang zu einem Workout besteht (z. B. Hypo während oder kurz nach dem Sport), und weise {userName} darauf hin, statt Sport- und Glukosedaten isoliert zu betrachten.

3. Körperzusammensetzung (Withings / Health Connect MCP)
- Sofern ein Server der Kategorie "Körperzusammensetzung" konfiguriert ist, hast du zusätzlich Zugriff auf Gewicht, Muskelanteil, Körperfett % und Schritte.
- Beziehe diese Daten ein, wenn sie für die Frage relevant sind (z. B. Gewichtsverlauf, Zusammenhang zwischen Gewichtsveränderung und Insulinbedarf) -- sowohl im Chat als auch in der Zusammenfassung/den Tipps der Übersicht, wenn ein solcher Server verbunden ist.

# ALLGEMEINE VERHALTENSREGELN & FORMATIERUNG
- **Tool-First-Prinzip:** Wenn eine Frage mit realen Daten aus EINER DER Anbindungen beantwortet werden kann -- Nightscout, Glooko, Google Fit/Health Connect (Sport), Withings/Health Connect (Gewicht, Körperzusammensetzung) -- führe IMMER zuerst den zu GENAU DIESEM Thema passenden MCP-Tool-Aufruf durch. Rate niemals Werte oder Zustände und achte auf die korrekte Zeit (Zeitzone). Eine Frage zu Gewicht/Körperzusammensetzung ist NIEMALS mit Blutzucker-/Glukose-Tools zu beantworten, auch wenn du primär ein Diabetes-Assistent bist -- orientiere dich strikt am tatsächlichen Thema der Frage und an der Tool-Registry, nicht an einer Standard-Annahme "es geht um Diabetes".
- **Priorität für aktuelle Werte (< 2 Stunden):** Fragt {userName} nach dem AKTUELLEN oder gerade eben gemessenen Blutzuckerwert bzw. einem Zeitraum unter 2 Stunden (z. B. "Wie ist mein Wert gerade?", "letzte Stunde"), nutze VORRANGIG Nightscout (MCP-Tool oder direkte REST-API, je nachdem was gerade verfügbar ist) -- Nightscout liefert die aktuellsten Sensorwerte. Weiche nur dann auf eine andere Quelle (z. B. Glooko) aus, wenn kein Nightscout konfiguriert oder erreichbar ist.
- **Sofort handeln, nicht nur ankündigen:** Wenn ein passendes Tool zur Verfügung steht, rufe es SOFORT in diesem Turn auf. Antworte NIEMALS nur mit einer Ankündigung wie "Lass mich das abrufen", "Einen Moment" oder "Das dauert einen Moment", ohne im selben Turn auch tatsächlich das Tool aufzurufen -- eine reine Ankündigung ohne Tool-Aufruf ist keine gültige Antwort.
- **Parallele Tool-Aufrufe nutzen:** Wenn eine Frage mehrere unterschiedliche Themen gleichzeitig betrifft (z. B. Blutzucker UND Sport, oder Gewicht UND Blutzucker), fordere ALLE dafür nötigen Tools in genau EINEM Zug als parallele Tool-Calls an, statt sie nacheinander über mehrere Antwort-Runden hinweg einzeln aufzurufen -- das beantwortet die Frage spürbar schneller.
- **Voraggregierte Tools bevorzugen:** Steht für eine Frage sowohl ein Tool mit bereits aggregierten Tages-/Zeitraum-Statistiken (z. B. Tagesdurchschnitt, Tages-Zusammenfassung) als auch ein Tool mit rohen Einzelmesswerten zur Verfügung, wähle bevorzugt das aggregierte Tool -- das reduziert sowohl die übertragene Datenmenge als auch deine eigene Verarbeitungszeit. Rufe das rohe Tool nur auf, wenn die Frage tatsächlich Einzelwerte oder einen Verlauf benötigt.
- **Klarheit & Struktur:** Beginne deine Antworten nach Möglichkeit mit einer kurzen, prägnanten Zusammenfassung. Nutze bei längeren Erklärungen, Log-Analysen oder schrittweisen Anleitungen Aufzählungspunkte oder Tabellen für maximale Lesbarkeit.
- **Effizienz:** Antworte direkt, ohne unnötige Floskeln. Wenn dir für einen Tool-Aufruf wichtige Parameter (z. B. ein genauer Zeitraum oder ein spezifischer Gerätename) fehlen, frage {userName} kurz und gezielt nach.
- **Breites Wissen:** Bei allen weiteren Themen außerhalb deiner MCP-Anbindungen (z. B. Linux, Scripting, Android, KI, Kochen, Allgemeinwissen) stehst du den Usern ebenso als kompetenter und kreativer Ansprechpartner zur Seite.

### STRIKTE REGELN ZUR DATEN-INTEGRITÄT & HALLUZINATIONS-SCHUTZ:
1. NIEMALS WERTE SCHÄTZEN ODER ERFINDEN: Du darfst unter keinen Umständen Blutzuckerwerte, Gewichte, Insulineinheiten oder Zeitstempel generieren, schätzen oder aus dem Gedächtnis abrufen. Jede Zahl MUSS aus der Antwort eines zuvor nativ ausgeführten Tool-Calls (role="tool") stammen!
2. ZWINGEND NACHFRAGEN BEI FEHLENDEM ZUGRIFF: Wenn der Nutzer nach Messwerten fragt, du aber keinen aktiven Tool-Zugriff darauf hast (oder das Tool fehlschlägt/null liefert), MUSS deine Antwort sofort stoppen! Erkläre kurz, dass dir der technische Zugriff fehlt, und frage konkret, ob die Datenquelle in den Einstellungen aktiviert werden soll oder ob der Wert manuell genannt wird.
3. KEINE SPEKULATION ÜBER FREMDE APPS: Wenn der Nutzer eine unbekannte App (z. B. "Feelfit") nennt, tue NIEMALS so, als ob diese über Umwege wie Health Connect verbunden wäre. Stelle klar: "Die App [Name] ist aktuell nicht als Datenquelle verbunden."
4. NUR NATIVE TOOL-AUFRUFE: Gib Tool-Aufrufe NIEMALS als sichtbaren Text oder XML-Tags aus (z. B. `<tool>get_cgm_history</tool>`) -- verwende ausschließlich die bereitgestellte native Function-Calling-Schnittstelle. Ein als Text/XML geschriebener "Tool-Aufruf" wird nicht ausgeführt und zählt als Regelverstoß.

# ZEITZONE & EINHEITEN
Vor jeder Nachricht erhältst du einen aktuellen `[SYSTEM STATE]`-Block mit den gerade aktiven Datenquellen, der verwendeten Maßeinheit und der lokalen Uhrzeit inkl. Zeitzone. Rechne jeden UTC-Zeitstempel aus einem Tool-Ergebnis (z. B. von Glooko oder Nightscout) verbindlich anhand dieser Zeitzone in die lokale Tageszeit um, bevor du ihn nennst oder damit vergleichst -- verliere dich nicht in eigenen Denkschleifen über die Zeitdifferenz. Nenne Blutzuckerwerte immer in der im `[SYSTEM STATE]`-Block angegebenen Maßeinheit.
"""

private const val USER_NAME_PLACEHOLDER = "{userName}"
private const val DEFAULT_USER_NAME_FALLBACK = "dem Nutzer"

data class AppSettings(
    val modelFilePath: String = "",
    val modelDownloadUrl: String = DEFAULT_MODEL_DOWNLOAD_URL,
    val userName: String = "",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val nightscoutApiUrl: String = "",
    val nightscoutApiSecret: String = "",
    val nightscoutApiAuthMethod: AuthMethod = AuthMethod.API_SECRET_HEADER,
    /** User-editable display name for the direct Nightscout REST API source (see
     * [com.example.diabai.network.nightscoutDirectServer]) -- blank falls back to plain
     * "Nightscout", same editable-name pattern every [McpServerConfig] already has. */
    val nightscoutApiName: String = "",
    /** Whether the direct Nightscout REST API is actually queried -- defaults to true so
     * decoding an already-persisted config saved before this field existed keeps behaving as
     * before (queried whenever a URL is set), mirroring [McpServerConfig.enabled]'s same
     * backward-compat default. */
    val nightscoutApiEnabled: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** System-Prompt-Schutzschicht (item 2): dynamically appended to the (for non-Entwickler
     * tiers, hidden) [systemPrompt] -- see [personalizedSystemPrompt]/[anonymizedSystemPrompt].
     * The one prompt-customization surface every tier can use, editable regardless of
     * [licenseTier] (only the PROTECTED base [systemPrompt] itself is Entwickler-only). */
    val additionalInstructions: String = "",
    val chatSessions: List<ChatSession> = emptyList(),
    val llmProviderType: LlmProviderType = LlmProviderType.LOCAL,
    val geminiApiKey: String = "",
    val claudeApiKey: String = "",
    /** Anthropic's own Messages API by default -- overridable to an Anthropic-compatible gateway
     * (e.g. "OneProvider", `https://api.oneprovider.dev/v1`) exactly like [openAiBaseUrl] already
     * is for OpenAI/OpenRouter/Ollama. Same request/response shape either way (tool_use/
     * tool_result content blocks, SSE streaming) -- only the host changes, see
     * [com.example.diabai.domain.llm.ClaudeApiProvider]. */
    val claudeBaseUrl: String = DEFAULT_CLAUDE_BASE_URL,
    val openAiApiKey: String = "",
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    /** [AUTO_MODEL_ID] or one of [ModelCatalog.optionsFor]'s ids -- see
     * [com.example.diabai.domain.llm.LLMProviderManager.resolve]. */
    val geminiModel: String = AUTO_MODEL_ID,
    val claudeModel: String = AUTO_MODEL_ID,
    val openAiModel: String = AUTO_MODEL_ID,
    val deepseekApiKey: String = "",
    val deepseekModel: String = AUTO_MODEL_ID,
    /** UI/voice language -- see [AppLanguage]'s doc comment for exactly what this does and
     * doesn't localize yet. */
    val appLanguage: AppLanguage = AppLanguage.GERMAN,
    /** "Einstellungen -> Profil / Benutzer" -- see [UserRole]'s doc comment. Drives tone/focus of
     * the system prompt via [personalizedSystemPrompt]/[anonymizedSystemPrompt]'s `withUserRole`. */
    val userRole: UserRole = UserRole.DIABETIKER,
    /** "Einstellungen -> Erscheinungsbild" -- see [AppColorTheme]. */
    val colorTheme: AppColorTheme = AppColorTheme.MEDICAL_BLUE,
    /** DownloadManager request id of an in-flight model download, if any (survives process death). */
    val activeDownloadId: Long? = null,
    /** Cloud-LLM token usage, last [LLM_USAGE_RETENTION_DAYS] days -- see "LLM-Verbrauch &
     * Kostenübersicht" in Über GlucoSphere. Never includes local-model usage (free, on-device). */
    val llmUsage: List<DailyLlmUsage> = emptyList(),
    /** Lizenzmodell (item 1) -- raw entered key, validated on read via [licenseTier]. Blank/
     * invalid -> [LicenseTier.FREE], never a crash (see [LicenseKeyValidator.validate]). */
    val licenseKey: String = "",
    /** Today's cumulative active-foreground milliseconds (see [SettingsRepository.addActiveUsageMillis]/
     * `MainActivity`'s onResume/onPause) -- already reset to 0 by [SettingsRepository.settings]'s
     * own mapping whenever the stored date key no longer matches today, so this is always
     * "today's" total, never a stale prior day's. */
    val dailyActiveUsageMillis: Long = 0L,
    /** OneProvider-Freikontingent (item 3): how many free-tier requests already happened today
     * (UTC day, see [todayUsageDateKeyUtc]) -- same "already reset if stale" guarantee as
     * [dailyActiveUsageMillis], just on the UTC calendar instead of the device's local one. Only
     * ever incremented/checked while [oneProviderApiKey] is blank -- see [isOneProviderOwnKey]. */
    val oneProviderFreeRequestsToday: Int = 0,
    /** Optional own key for [LlmProviderType.ONEPROVIDER_FREE] -- blank (the default) means "use
     * the app-embedded free-tier key", gated by [oneProviderFreeRequestsToday]; non-blank means
     * "use this key instead", exactly like every other cloud provider's own key, no daily limit. */
    val oneProviderApiKey: String = "",
    /** [AUTO_MODEL_ID] or one of [ModelCatalog.optionsFor]'s ids -- applies regardless of which of
     * the two [oneProviderApiKey] modes above is active. */
    val oneProviderModel: String = AUTO_MODEL_ID,
    /** "Debug-Log (Entwickler)" in Performance-Log -- off by default (see [DebugLogEntry]'s doc
     * comment for why this is opt-in rather than always-on like [LlmRequestLogEntry]). Only
     * meaningful/toggleable behind [LicenseTier.DEVELOPER] in the UI, but not itself license-
     * checked here -- an already-enabled toggle simply keeps working if a license key expires/is
     * removed, same as every other setting in this file. */
    val debugLoggingEnabled: Boolean = false,
) {
    /** True once the user has entered their own OneProvider key -- switches
     * [com.example.diabai.domain.llm.LLMProviderManager]/[com.example.diabai.domain.DiabetesAgent]
     * from the app-embedded free-tier key + daily cap over to an unlimited, self-supplied key,
     * same as every other cloud provider. */
    val isOneProviderOwnKey: Boolean
        get() = oneProviderApiKey.isNotBlank()

    /** [LicenseKeyValidator.validate]'s result, defaulting to [LicenseTier.FREE] for a blank or
     * invalid key -- the single source of truth every tier-gated feature (daily usage cap,
     * System-Prompt visibility) reads from. */
    val licenseTier: LicenseTier
        get() = LicenseKeyValidator.validate(licenseKey) ?: LicenseTier.FREE

    /** Null = unlimited ([LicenseTier.USER]/[LicenseTier.DEVELOPER]). */
    val dailyUsageLimitMillis: Long?
        get() = licenseTier.dailyLimitMillis()

    /** Gates chat/Übersicht inference (item 1) -- checked by [com.example.diabai.domain.DiabetesAgent]
     * before ever starting a conversation, so a Test/Free-tier user who's used up today's budget
     * never even reaches the network. */
    val isDailyUsageLimitReached: Boolean
        get() = dailyUsageLimitMillis?.let { dailyActiveUsageMillis >= it } ?: false

    /** For the "Über GlucoSphere" screen's remaining-time display -- null for unlimited tiers. */
    val remainingDailyUsageMillis: Long?
        get() = dailyUsageLimitMillis?.let { (it - dailyActiveUsageMillis).coerceAtLeast(0L) }
    /** No MCP server and no direct Nightscout REST API configured -> chat-only, no tool calls. */
    val isPureChatMode: Boolean
        get() = mcpServers.isEmpty() && nightscoutApiUrl.isBlank()

    /** [systemPrompt] with `{userName}` substituted -- what actually gets sent to the model.
     * `String.replace(String, String)` (used here, not a `Regex`) only ever matches the exact
     * literal `{userName}` substring -- braces included -- so it can never partially match an
     * ordinary word like "Fragen" that merely shares a few letters. */
    val personalizedSystemPrompt: String
        get() = withAdditionalInstructions(withUserRole(systemPrompt.replace(USER_NAME_PLACEHOLDER, userName.trim().ifBlank { DEFAULT_USER_NAME_FALLBACK })))

    /** Same substitution as [personalizedSystemPrompt], but with the placeholder generic-ized
     * instead of filled with the real name -- what cloud providers get instead (see
     * [com.example.diabai.domain.DiabetesAgent]'s `personaPromptFor`), since the whole point of
     * the privacy shield is that a cloud LLM never sees the user's actual name. */
    val anonymizedSystemPrompt: String
        get() = withAdditionalInstructions(withUserRole(systemPrompt.replace(USER_NAME_PLACEHOLDER, "[USER]")))

    /** Appends a role-specific tone/focus block for [userRole] to [base] -- "Einstellungen ->
     * Profil / Benutzer": the same underlying data (Blutzuckerwerte, TIR, ...) needs a different
     * voice depending on who's actually reading the answer (see [UserRole]'s doc comment).
     * Applied BEFORE [withAdditionalInstructions] so a user's own custom instructions (item 2)
     * still have the final say if the two ever conflict. */
    private fun withUserRole(base: String): String = "$base\n\n${rolePromptFor(userRole)}"

    /** Appends [additionalInstructions] (item 2's "Zusätzliche Instruktionen" layer) to [base] --
     * shared by [personalizedSystemPrompt]/[anonymizedSystemPrompt] so every tier's custom
     * instructions apply regardless of provider/privacy-shield path. A no-op when blank, so a
     * user who's never touched the field gets byte-identical behavior to before this existed. */
    private fun withAdditionalInstructions(base: String): String =
        if (additionalInstructions.isBlank()) {
            base
        } else {
            "$base\n\n# ZUSÄTZLICHE INSTRUKTIONEN\n${additionalInstructions.trim()}"
        }

    /** "Provider · Modell" shown directly under the TopAppBar title (see MainTabsScreen), so the
     * currently active LLM is always visible without opening Einstellungen -- e.g.
     * "LiteRT · Gemma 4 E2B" or "Anthropic · Claude Sonnet 4.5" or "Google · Automatisch". Model
     * names/labels come from [ModelCatalog], the single shared source of truth for what each
     * provider's model ids are actually called. */
    val activeLlmLabel: String
        get() = when (llmProviderType) {
            LlmProviderType.LOCAL -> if (modelFilePath.isBlank()) {
                "Lokales Modell · nicht geladen"
            } else {
                "LiteRT · ${localModelDisplayName(modelFilePath)}"
            }
            LlmProviderType.GEMINI ->
                if (geminiApiKey.isBlank()) "Google Gemini · kein API-Key" else "Google · ${modelDisplayName(LlmProviderType.GEMINI, geminiModel)}"
            LlmProviderType.CLAUDE ->
                if (claudeApiKey.isBlank()) "Anthropic Claude · kein API-Key" else "Anthropic · ${modelDisplayName(LlmProviderType.CLAUDE, claudeModel)}"
            LlmProviderType.OPENAI -> when {
                openAiApiKey.isBlank() -> "OpenAI/OpenRouter · kein API-Key"
                openAiBaseUrl.contains("openrouter", ignoreCase = true) ->
                    "OpenRouter · ${modelDisplayName(LlmProviderType.OPENAI, openAiModel)}"
                else -> "OpenAI · ${modelDisplayName(LlmProviderType.OPENAI, openAiModel)}"
            }
            LlmProviderType.DEEPSEEK ->
                if (deepseekApiKey.isBlank()) "DeepSeek · kein API-Key" else "DeepSeek · ${modelDisplayName(LlmProviderType.DEEPSEEK, deepseekModel)}"
            LlmProviderType.ONEPROVIDER_FREE -> if (isOneProviderOwnKey) {
                "OneProvider · ${modelDisplayName(LlmProviderType.ONEPROVIDER_FREE, oneProviderModel)}"
            } else {
                val remaining = (ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT - oneProviderFreeRequestsToday).coerceAtLeast(0)
                "OneProvider · Freikontingent ($remaining/$ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT heute übrig)"
            }
        }
}

/** The role-specific tone/focus block [AppSettings.withUserRole] appends to the system prompt --
 * one fixed German text per [UserRole], not user-editable (unlike [AppSettings.additionalInstructions]),
 * so switching the dropdown in "Profil / Benutzer" has an immediate, predictable effect. */
private fun rolePromptFor(role: UserRole): String = when (role) {
    UserRole.DIABETIKER -> """
# ROLLENSPEZIFISCHE ANWEISUNGEN: DIABETIKER
Der Nutzer ist selbst Diabetiker/Diabetikerin. Sprich ihn persönlich per Du an, empathisch, praxisorientiert und auf Augenhöhe. Fokussiere auf konkrete Alltagstipps: Blutzuckermanagement, KE-/BE-Schätzungen bei Ernährungsfragen, sowie die Auswirkungen von Sport und Medikation auf den Blutzucker. Drücke dich leicht verständlich aus und vermeide unnötiges Fachchinesisch -- ist ein medizinischer Fachbegriff nötig, erkläre ihn kurz und prägnant.
    """.trimIndent()
    UserRole.FACHPERSONAL -> """
# ROLLENSPEZIFISCHE ANWEISUNGEN: MEDIZINISCHES FACHPERSONAL
Der Nutzer ist medizinisches Fachpersonal (Diabetes-Team). Antworte professionell, sachlich und hochpräzise -- in der Anrede standardmäßig fachlich-neutral, außer der Nutzer gibt Du/Sie explizit vor. Fokussiere auf objektive Datenanalysen: Time-in-Range (TIR), Standardabweichung, Variationskoeffizient (%CV), AGP-Profile, Insulindosierungs-Schemata und Leitlinien-Konformität. Verwende medizinische Fachsprache (z. B. Basalrate, Korrekturfaktor, HbA1c-Äquivalent, Bolus-Timing) ohne Grundbegriffe zu erklären.
    """.trimIndent()
    UserRole.ANGEHOERIGE -> """
# ROLLENSPEZIFISCHE ANWEISUNGEN: ANGEHÖRIGE
Der Nutzer ist ein Angehöriger/eine Angehörige ohne medizinischen Hintergrund. Antworte einfühlsam, beruhigend, verständnisvoll und klar. Erkläre aktuelle Werte verständlich, weise proaktiv auf Notfall-Signale hin (Über-/Unterzuckerung) und gib konkrete Handlungsempfehlungen ("Was ist jetzt zu tun?"). Sprich absolut barrierefrei -- vermeide Fachbegriffe komplett oder erkläre sie sofort mit einfachen Analogien (z. B. "Unterzuckerung" statt nur "Hypo").
    """.trimIndent()
}

/** Turns a model filename like `gemma-4-E2B-it.litertlm` into a short display name ("Gemma 4
 * E2B") for [AppSettings.activeLlmLabel] -- drops the `it` (instruction-tuned) suffix and the
 * file extension, title-cases the rest. */
private fun localModelDisplayName(path: String): String {
    val base = File(path).nameWithoutExtension
    val tokens = base.split('-', '_').filterNot { it.equals("it", ignoreCase = true) }
    return tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.ifBlank { base }
}

/** Short display form of a model selection for [AppSettings.activeLlmLabel] -- "Automatisch" for
 * [AUTO_MODEL_ID], otherwise the catalog label with its "(schnell)"/"(leistungsstark)" suffix
 * trimmed off (that detail belongs in the settings dropdown, not the compact header line). */
private fun modelDisplayName(type: LlmProviderType, selection: String): String {
    if (selection == AUTO_MODEL_ID) return "Automatisch"
    val label = ModelCatalog.optionsFor(type).firstOrNull { it.id == selection }?.label ?: selection
    return label.substringBefore(" (")
}

/** Persists user-configurable settings (model file location, data source credentials, system prompt) via DataStore. */
class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext

    private object Keys {
        val MODEL_FILE_PATH = stringPreferencesKey("model_file_path")
        val MODEL_DOWNLOAD_URL = stringPreferencesKey("model_download_url")
        val USER_NAME = stringPreferencesKey("user_name")
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val NIGHTSCOUT_API_URL = stringPreferencesKey("nightscout_api_url")
        val NIGHTSCOUT_API_SECRET = stringPreferencesKey("nightscout_api_secret")
        val NIGHTSCOUT_API_AUTH_METHOD = stringPreferencesKey("nightscout_api_auth_method")
        val NIGHTSCOUT_API_ENABLED = booleanPreferencesKey("nightscout_api_enabled")
        val NIGHTSCOUT_API_NAME = stringPreferencesKey("nightscout_api_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val CHAT_SESSIONS = stringPreferencesKey("chat_sessions")
        val LLM_PROVIDER_TYPE = stringPreferencesKey("llm_provider_type")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val CLAUDE_BASE_URL = stringPreferencesKey("claude_base_url")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
        val DEEPSEEK_MODEL = stringPreferencesKey("deepseek_model")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val USER_ROLE = stringPreferencesKey("user_role")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        val ACTIVE_DOWNLOAD_ID = longPreferencesKey("active_download_id")
        val LLM_USAGE = stringPreferencesKey("llm_usage")
        val LLM_REQUEST_LOG = stringPreferencesKey("llm_request_log")
        val DASHBOARD_CACHE = stringPreferencesKey("dashboard_cache")
        val DISCOVERY_RECORDS = stringPreferencesKey("discovery_records")
        val LICENSE_KEY = stringPreferencesKey("license_key")
        val ADDITIONAL_INSTRUCTIONS = stringPreferencesKey("additional_instructions")
        val DAILY_ACTIVE_USAGE_MILLIS = longPreferencesKey("daily_active_usage_millis")
        val DAILY_ACTIVE_USAGE_DATE_KEY = stringPreferencesKey("daily_active_usage_date_key")
        val ONEPROVIDER_FREE_REQUESTS_TODAY = intPreferencesKey("oneprovider_free_requests_today")
        val ONEPROVIDER_FREE_REQUESTS_DATE_KEY_UTC = stringPreferencesKey("oneprovider_free_requests_date_key_utc")
        val ONEPROVIDER_API_KEY = stringPreferencesKey("oneprovider_api_key")
        val ONEPROVIDER_MODEL = stringPreferencesKey("oneprovider_model")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_LOG = stringPreferencesKey("debug_log")
    }

    val settings: Flow<AppSettings> = appContext.dataStore.data.map { prefs ->
        AppSettings(
            modelFilePath = prefs[Keys.MODEL_FILE_PATH].orEmpty(),
            modelDownloadUrl = prefs[Keys.MODEL_DOWNLOAD_URL] ?: DEFAULT_MODEL_DOWNLOAD_URL,
            userName = prefs[Keys.USER_NAME].orEmpty(),
            mcpServers = prefs[Keys.MCP_SERVERS].orEmpty().decodeMcpServerConfigs(),
            nightscoutApiUrl = prefs[Keys.NIGHTSCOUT_API_URL].orEmpty(),
            nightscoutApiSecret = prefs[Keys.NIGHTSCOUT_API_SECRET].orEmpty(),
            nightscoutApiAuthMethod = prefs[Keys.NIGHTSCOUT_API_AUTH_METHOD].toAuthMethod(AuthMethod.API_SECRET_HEADER),
            nightscoutApiEnabled = prefs[Keys.NIGHTSCOUT_API_ENABLED] ?: true,
            nightscoutApiName = prefs[Keys.NIGHTSCOUT_API_NAME].orEmpty(),
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            chatSessions = prefs[Keys.CHAT_SESSIONS].orEmpty().decodeChatSessions(),
            llmProviderType = prefs[Keys.LLM_PROVIDER_TYPE].toLlmProviderType(),
            geminiApiKey = prefs[Keys.GEMINI_API_KEY].orEmpty(),
            claudeApiKey = prefs[Keys.CLAUDE_API_KEY].orEmpty(),
            claudeBaseUrl = prefs[Keys.CLAUDE_BASE_URL] ?: DEFAULT_CLAUDE_BASE_URL,
            openAiApiKey = prefs[Keys.OPENAI_API_KEY].orEmpty(),
            openAiBaseUrl = prefs[Keys.OPENAI_BASE_URL] ?: DEFAULT_OPENAI_BASE_URL,
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: AUTO_MODEL_ID,
            claudeModel = prefs[Keys.CLAUDE_MODEL] ?: AUTO_MODEL_ID,
            openAiModel = prefs[Keys.OPENAI_MODEL] ?: AUTO_MODEL_ID,
            deepseekApiKey = prefs[Keys.DEEPSEEK_API_KEY].orEmpty(),
            deepseekModel = prefs[Keys.DEEPSEEK_MODEL] ?: AUTO_MODEL_ID,
            appLanguage = prefs[Keys.APP_LANGUAGE].toAppLanguage(),
            userRole = prefs[Keys.USER_ROLE].toUserRole(),
            colorTheme = prefs[Keys.COLOR_THEME].toAppColorTheme(),
            activeDownloadId = prefs[Keys.ACTIVE_DOWNLOAD_ID],
            llmUsage = prefs[Keys.LLM_USAGE].orEmpty().decodeDailyLlmUsage(),
            additionalInstructions = prefs[Keys.ADDITIONAL_INSTRUCTIONS].orEmpty(),
            licenseKey = prefs[Keys.LICENSE_KEY].orEmpty(),
            // Already reset to 0 here (not just on the next write) whenever the stored date key
            // is stale -- so a StateFlow collector reading `settings` right after local midnight,
            // before any new usage has been recorded yet, still sees today's true (zero) total
            // instead of yesterday's leftover number.
            dailyActiveUsageMillis = if (prefs[Keys.DAILY_ACTIVE_USAGE_DATE_KEY] == todayUsageDateKey()) {
                prefs[Keys.DAILY_ACTIVE_USAGE_MILLIS] ?: 0L
            } else {
                0L
            },
            oneProviderFreeRequestsToday = if (prefs[Keys.ONEPROVIDER_FREE_REQUESTS_DATE_KEY_UTC] == todayUsageDateKeyUtc()) {
                prefs[Keys.ONEPROVIDER_FREE_REQUESTS_TODAY] ?: 0
            } else {
                0
            },
            oneProviderApiKey = prefs[Keys.ONEPROVIDER_API_KEY].orEmpty(),
            oneProviderModel = prefs[Keys.ONEPROVIDER_MODEL] ?: AUTO_MODEL_ID,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
        )
    }

    suspend fun saveDebugLoggingEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    /** Same one-shot-suspend-read pattern as [loadLlmRequestLog] -- read on demand by
     * Performance-Log's "Debug-Log (Entwickler)" section, not observed reactively. */
    suspend fun loadDebugLog(): List<DebugLogEntry> =
        appContext.dataStore.data.first()[Keys.DEBUG_LOG].orEmpty().decodeDebugLog()

    /** Called from [com.example.diabai.domain.DiabetesAgent] only while
     * [AppSettings.debugLoggingEnabled] is on -- see [DebugLogEntry]'s doc comment. */
    suspend fun appendDebugLog(message: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DEBUG_LOG].orEmpty().decodeDebugLog()
            prefs[Keys.DEBUG_LOG] = current.addDebugEntry(DebugLogEntry(System.currentTimeMillis(), message)).encodeToJson()
        }
    }

    suspend fun clearDebugLog() {
        appContext.dataStore.edit { it[Keys.DEBUG_LOG] = emptyList<DebugLogEntry>().encodeToJson() }
    }

    suspend fun saveModelFilePath(path: String) {
        appContext.dataStore.edit { it[Keys.MODEL_FILE_PATH] = path }
    }

    suspend fun saveModelDownloadUrl(url: String) {
        appContext.dataStore.edit { it[Keys.MODEL_DOWNLOAD_URL] = url }
    }

    suspend fun saveActiveDownloadId(id: Long?) {
        appContext.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_DOWNLOAD_ID) else prefs[Keys.ACTIVE_DOWNLOAD_ID] = id
        }
    }

    suspend fun saveUserName(name: String) {
        appContext.dataStore.edit { it[Keys.USER_NAME] = name }
    }

    suspend fun saveMcpServers(servers: List<McpServerConfig>) {
        appContext.dataStore.edit { it[Keys.MCP_SERVERS] = servers.take(MAX_MCP_SERVERS).encodeToJson() }
    }

    suspend fun saveNightscoutApi(url: String, secret: String, authMethod: AuthMethod, enabled: Boolean, name: String = "") {
        appContext.dataStore.edit {
            it[Keys.NIGHTSCOUT_API_URL] = url
            it[Keys.NIGHTSCOUT_API_SECRET] = secret
            it[Keys.NIGHTSCOUT_API_AUTH_METHOD] = authMethod.name
            it[Keys.NIGHTSCOUT_API_ENABLED] = enabled
            it[Keys.NIGHTSCOUT_API_NAME] = name
        }
    }

    suspend fun saveSystemPrompt(prompt: String) {
        appContext.dataStore.edit { it[Keys.SYSTEM_PROMPT] = prompt }
    }

    /** Item 2's "Zusätzliche Instruktionen" -- the one prompt field every [LicenseTier] can edit,
     * regardless of whether the protected base [AppSettings.systemPrompt] itself is visible. */
    suspend fun saveAdditionalInstructions(text: String) {
        appContext.dataStore.edit { it[Keys.ADDITIONAL_INSTRUCTIONS] = text }
    }

    /** "Über GlucoSphere"'s Lizenzschlüssel field (item 1) -- validated lazily on every
     * [AppSettings.licenseTier] read, not here, so an invalid/mistyped key never throws, it just
     * silently keeps [LicenseTier.FREE]. */
    suspend fun saveLicenseKey(key: String) {
        appContext.dataStore.edit { it[Keys.LICENSE_KEY] = key.trim() }
    }

    /** Called from `MainActivity`'s onPause with however many milliseconds the app was just in
     * the foreground for (item 1's "aktive Nutzungszeit") -- resets to [deltaMillis] alone
     * instead of adding onto a stale total whenever the stored date key isn't today's (local
     * midnight rollover), mirroring the same reset-on-stale-key pattern [AppSettings]'s own
     * mapping already applies on read. */
    suspend fun addActiveUsageMillis(deltaMillis: Long) {
        if (deltaMillis <= 0) return
        appContext.dataStore.edit { prefs ->
            val todayKey = todayUsageDateKey()
            val current = if (prefs[Keys.DAILY_ACTIVE_USAGE_DATE_KEY] == todayKey) prefs[Keys.DAILY_ACTIVE_USAGE_MILLIS] ?: 0L else 0L
            prefs[Keys.DAILY_ACTIVE_USAGE_DATE_KEY] = todayKey
            prefs[Keys.DAILY_ACTIVE_USAGE_MILLIS] = current + deltaMillis
        }
    }

    /** Called once per OneProvider-Freikontingent request actually sent (item 3), right before
     * dispatch -- see [com.example.diabai.domain.DiabetesAgent]. UTC-keyed, resets independently
     * of [addActiveUsageMillis]'s local-date bucket. */
    suspend fun recordOneProviderFreeRequest() {
        appContext.dataStore.edit { prefs ->
            val todayKey = todayUsageDateKeyUtc()
            val current = if (prefs[Keys.ONEPROVIDER_FREE_REQUESTS_DATE_KEY_UTC] == todayKey) prefs[Keys.ONEPROVIDER_FREE_REQUESTS_TODAY] ?: 0 else 0
            prefs[Keys.ONEPROVIDER_FREE_REQUESTS_DATE_KEY_UTC] = todayKey
            prefs[Keys.ONEPROVIDER_FREE_REQUESTS_TODAY] = current + 1
        }
    }

    suspend fun saveChatSession(session: ChatSession) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_SESSIONS].orEmpty().decodeChatSessions()
            prefs[Keys.CHAT_SESSIONS] = current.upsert(session).encodeToJson()
        }
    }

    /** "Chatverlauf löschen" in the Chat tab's TopAppBar -- wipes every saved session, not just
     * the one currently open (that alone is [GlucoSphereViewModel.startNewChat], no persistence
     * involved). */
    suspend fun clearChatSessions() {
        appContext.dataStore.edit { it[Keys.CHAT_SESSIONS] = emptyList<ChatSession>().encodeToJson() }
    }

    /** Switches the active provider without touching any stored key -- used for "Lokales
     * Modell", which needs no credentials and so has nothing to gate behind a key test. */
    suspend fun saveLlmProviderType(type: LlmProviderType) {
        appContext.dataStore.edit { it[Keys.LLM_PROVIDER_TYPE] = type.name }
    }

    /** Persists a cloud provider's credentials together with switching to it -- mirrors the
     * MCP/Nightscout "test, then save" gating so the active provider is never pointed at an
     * untested key. */
    suspend fun saveLlmProviderConfig(type: LlmProviderType, apiKey: String, baseUrl: String) {
        appContext.dataStore.edit { prefs ->
            // Only actually switches the ACTIVE provider when there's something valid to switch
            // to -- a blank key means "clear this provider's stored credential" (see
            // LlmConfigScreen's ProviderKeyForm, which now allows saving a blank key specifically
            // so a key CAN be removed again), not "activate it with no key", for every provider
            // except ONEPROVIDER_FREE (blank there is itself the intended, fully valid "use the
            // app's free-tier key" activation -- see AppSettings.isOneProviderOwnKey). Without
            // this, clearing e.g. a stored-but-unused Claude key while Gemini is active would
            // silently switch the app over to a now-keyless, non-functional Claude.
            if (apiKey.isNotBlank() || type == LlmProviderType.ONEPROVIDER_FREE) {
                prefs[Keys.LLM_PROVIDER_TYPE] = type.name
            }
            when (type) {
                LlmProviderType.GEMINI -> prefs[Keys.GEMINI_API_KEY] = apiKey
                LlmProviderType.CLAUDE -> {
                    prefs[Keys.CLAUDE_API_KEY] = apiKey
                    prefs[Keys.CLAUDE_BASE_URL] = baseUrl.trim().ifBlank { DEFAULT_CLAUDE_BASE_URL }
                }
                LlmProviderType.OPENAI -> {
                    prefs[Keys.OPENAI_API_KEY] = apiKey
                    prefs[Keys.OPENAI_BASE_URL] = baseUrl.trim().ifBlank { DEFAULT_OPENAI_BASE_URL }
                }
                LlmProviderType.DEEPSEEK -> prefs[Keys.DEEPSEEK_API_KEY] = apiKey
                // Optional: a blank apiKey here is a deliberate, valid choice (falls back to the
                // app-embedded free-tier key + daily cap, see AppSettings.isOneProviderOwnKey) --
                // unlike every other branch above, this must NOT be gated behind a "Testen" pass
                // when blank, see LlmConfigScreen's ProviderKeyForm(keyOptional = true).
                LlmProviderType.ONEPROVIDER_FREE -> prefs[Keys.ONEPROVIDER_API_KEY] = apiKey
                LlmProviderType.LOCAL -> Unit
            }
        }
    }

    /** Which concrete model a cloud provider uses ([AUTO_MODEL_ID] or one of
     * [ModelCatalog.optionsFor]'s ids) -- independent of [saveLlmProviderConfig] so changing the
     * model doesn't require re-entering/re-testing the API key. */
    suspend fun saveLlmModel(type: LlmProviderType, modelId: String) {
        appContext.dataStore.edit { prefs ->
            when (type) {
                LlmProviderType.GEMINI -> prefs[Keys.GEMINI_MODEL] = modelId
                LlmProviderType.CLAUDE -> prefs[Keys.CLAUDE_MODEL] = modelId
                LlmProviderType.OPENAI -> prefs[Keys.OPENAI_MODEL] = modelId
                LlmProviderType.DEEPSEEK -> prefs[Keys.DEEPSEEK_MODEL] = modelId
                LlmProviderType.ONEPROVIDER_FREE -> prefs[Keys.ONEPROVIDER_MODEL] = modelId
                LlmProviderType.LOCAL -> Unit
            }
        }
    }

    /** "Deutsch / English" toggle in Einstellungen -- see [AppLanguage]. */
    suspend fun saveAppLanguage(language: AppLanguage) {
        appContext.dataStore.edit { it[Keys.APP_LANGUAGE] = language.name }
    }

    /** "Einstellungen -> Profil / Benutzer" -- see [UserRole]. */
    suspend fun saveUserRole(role: UserRole) {
        appContext.dataStore.edit { it[Keys.USER_ROLE] = role.name }
    }

    /** "Einstellungen -> Erscheinungsbild" -- see [AppColorTheme]. */
    suspend fun saveColorTheme(theme: AppColorTheme) {
        appContext.dataStore.edit { it[Keys.COLOR_THEME] = theme.name }
    }

    /** Called once per cloud-LLM turn (see [com.example.diabai.domain.DiabetesAgent]) with
     * whatever token counts that provider's response reported -- never called for the local
     * model. Best-effort: a provider that doesn't report usage this turn simply isn't recorded,
     * rather than guessing. */
    suspend fun recordLlmUsage(promptTokens: Int, completionTokens: Int) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.LLM_USAGE].orEmpty().decodeDailyLlmUsage()
            prefs[Keys.LLM_USAGE] = current.addUsage(todayUsageDateKey(), promptTokens, completionTokens).encodeToJson()
        }
    }

    /** "Verbrauchsstatistik zurücksetzen" in Über GlucoSphere. */
    suspend fun resetLlmUsage() {
        appContext.dataStore.edit { it[Keys.LLM_USAGE] = emptyList<DailyLlmUsage>().encodeToJson() }
    }

    /** Performance-Log: same one-shot-suspend-read pattern as [loadDashboardCache]/
     * [loadAllDiscoveryRecords] -- changes on every LLM exchange (far more often than real
     * settings), read on demand by the Performance-Log screen rather than observed reactively. */
    suspend fun loadLlmRequestLog(): List<LlmRequestLogEntry> =
        appContext.dataStore.data.first()[Keys.LLM_REQUEST_LOG].orEmpty().decodeLlmRequestLog()

    /** Called once per completed LLM exchange (see [com.example.diabai.domain.DiabetesAgent.runToolLoop]),
     * success or failure alike. */
    suspend fun recordLlmRequestLog(entry: LlmRequestLogEntry) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.LLM_REQUEST_LOG].orEmpty().decodeLlmRequestLog()
            prefs[Keys.LLM_REQUEST_LOG] = current.addEntry(entry).encodeToJson()
        }
    }

    suspend fun clearLlmRequestLog() {
        appContext.dataStore.edit { it[Keys.LLM_REQUEST_LOG] = emptyList<LlmRequestLogEntry>().encodeToJson() }
    }

    /** Offline-first Übersicht cache (see [com.example.diabai.domain.analytics.DiabetesDashboardManager]
     * and `GlucoSphereViewModel`'s init) -- deliberately NOT folded into [settings]/[AppSettings]: it
     * changes on every dashboard refresh (far more often than real settings) and is read once at
     * startup rather than observed reactively, so putting it in the shared settings [Flow] would
     * trigger a recomposition everywhere [settings] is read on every single Übersicht refresh. A
     * single one-shot suspend read is also what "< 100 ms, no blocking spinner" actually needs --
     * a `Flow` collector would first need to be told "the first emission is enough", which is more
     * ceremony for no benefit here. */
    suspend fun loadDashboardCache(): DiabetesDashboard? =
        appContext.dataStore.data.first()[Keys.DASHBOARD_CACHE]?.decodeDashboardCache()

    suspend fun saveDashboardCache(dashboard: DiabetesDashboard) {
        appContext.dataStore.edit { it[Keys.DASHBOARD_CACHE] = dashboard.encodeToJson() }
    }

    /** Export-/Importfunktion (item 2): overwrites every field [SettingsBackup] covers in one
     * atomic `dataStore.edit {}` transaction -- deliberately not a sequence of the individual
     * `saveXxx` calls above (each its own separate `edit {}` write), so a collector of [settings]
     * (the whole rest of the app) can never observe a half-applied import (e.g. the new API key
     * already in place but the old system prompt still active) between two of them. Fields
     * [SettingsBackup] doesn't cover (model file path, chat history, dashboard cache, discovery
     * records -- see its own doc comment for why) are left completely untouched. */
    suspend fun restoreFromBackup(backup: SettingsBackup) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.USER_NAME] = backup.userName
            prefs[Keys.MCP_SERVERS] = backup.mcpServers.take(MAX_MCP_SERVERS).encodeToJson()
            prefs[Keys.NIGHTSCOUT_API_URL] = backup.nightscoutApiUrl
            prefs[Keys.NIGHTSCOUT_API_SECRET] = backup.nightscoutApiSecret
            prefs[Keys.NIGHTSCOUT_API_AUTH_METHOD] = backup.nightscoutApiAuthMethod.name
            prefs[Keys.NIGHTSCOUT_API_ENABLED] = backup.nightscoutApiEnabled
            prefs[Keys.NIGHTSCOUT_API_NAME] = backup.nightscoutApiName
            prefs[Keys.SYSTEM_PROMPT] = backup.systemPrompt
            prefs[Keys.ADDITIONAL_INSTRUCTIONS] = backup.additionalInstructions
            prefs[Keys.LICENSE_KEY] = backup.licenseKey
            prefs[Keys.LLM_PROVIDER_TYPE] = backup.llmProviderType.name
            prefs[Keys.GEMINI_API_KEY] = backup.geminiApiKey
            prefs[Keys.CLAUDE_API_KEY] = backup.claudeApiKey
            prefs[Keys.CLAUDE_BASE_URL] = backup.claudeBaseUrl
            prefs[Keys.OPENAI_API_KEY] = backup.openAiApiKey
            prefs[Keys.OPENAI_BASE_URL] = backup.openAiBaseUrl
            prefs[Keys.GEMINI_MODEL] = backup.geminiModel
            prefs[Keys.CLAUDE_MODEL] = backup.claudeModel
            prefs[Keys.OPENAI_MODEL] = backup.openAiModel
            prefs[Keys.DEEPSEEK_API_KEY] = backup.deepseekApiKey
            prefs[Keys.DEEPSEEK_MODEL] = backup.deepseekModel
            prefs[Keys.ONEPROVIDER_API_KEY] = backup.oneProviderApiKey
            prefs[Keys.ONEPROVIDER_MODEL] = backup.oneProviderModel
            prefs[Keys.APP_LANGUAGE] = backup.appLanguage.name
            prefs[Keys.USER_ROLE] = backup.userRole.name
            prefs[Keys.COLOR_THEME] = backup.colorTheme.name
            prefs[Keys.LLM_USAGE] = backup.llmUsage.encodeToJson()
        }
    }

    /** "Discovery Modus" (see [com.example.diabai.domain.discovery.DiscoveryService]): every data
     * source's discovered schema + LLM profile, keyed by its id -- same one-shot-suspend-read
     * pattern as [loadDashboardCache] (changes only on an explicit "Erkunden" tap or a fresh
     * install, read on demand rather than observed reactively). */
    suspend fun loadAllDiscoveryRecords(): Map<String, DiscoveryRecord> =
        appContext.dataStore.data.first()[Keys.DISCOVERY_RECORDS].orEmpty().decodeDiscoveryRecords()

    suspend fun loadDiscoveryRecord(serverId: String): DiscoveryRecord? = loadAllDiscoveryRecords()[serverId]

    suspend fun saveDiscoveryRecord(serverId: String, record: DiscoveryRecord) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DISCOVERY_RECORDS].orEmpty().decodeDiscoveryRecords()
            prefs[Keys.DISCOVERY_RECORDS] = (current + (serverId to record)).encodeToJson()
        }
    }

    /** Called when a data source is removed entirely (see [SettingsViewModel.removeMcpServer])
     * so a stale discovery record for a now-deleted server doesn't linger forever. */
    suspend fun removeDiscoveryRecord(serverId: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DISCOVERY_RECORDS].orEmpty().decodeDiscoveryRecords()
            prefs[Keys.DISCOVERY_RECORDS] = (current - serverId).encodeToJson()
        }
    }
}

private fun String?.toAuthMethod(default: AuthMethod): AuthMethod =
    this?.let { runCatching { AuthMethod.valueOf(it) }.getOrNull() } ?: default

private fun String?.toLlmProviderType(): LlmProviderType =
    this?.let { runCatching { LlmProviderType.valueOf(it) }.getOrNull() } ?: LlmProviderType.LOCAL

private fun String?.toAppLanguage(): AppLanguage =
    this?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.GERMAN

private fun String?.toUserRole(): UserRole =
    this?.let { runCatching { UserRole.valueOf(it) }.getOrNull() } ?: UserRole.DIABETIKER

private fun String?.toAppColorTheme(): AppColorTheme =
    this?.let { runCatching { AppColorTheme.valueOf(it) }.getOrNull() } ?: AppColorTheme.MEDICAL_BLUE
