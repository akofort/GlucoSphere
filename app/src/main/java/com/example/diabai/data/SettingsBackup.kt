package com.example.diabai.data

import com.example.diabai.domain.llm.AUTO_MODEL_ID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bumped only if a future field is removed or changes meaning in a way older backups can't be
 * read as-is -- a purely additive new field does NOT need a bump (kotlinx.serialization already
 * defaults it via `ignoreUnknownKeys`/the property's own default value on both sides). Checked by
 * [String.decodeSettingsBackup]'s caller before applying an import. */
const val SETTINGS_BACKUP_SCHEMA_VERSION = 1

/**
 * Export-/Import-Funktion (item 2): a deliberately curated snapshot of [AppSettings] -- exactly
 * the fields the request named ("API-Keys, ausgewählter LLMs, Custom Prompts, MCP-Server-URLs und
 * Token-Statistiken"), not the whole [AppSettings] object. Left out on purpose:
 *  - [AppSettings.modelFilePath]/[AppSettings.modelDownloadUrl]/[AppSettings.activeDownloadId] --
 *    an on-device file path / in-flight download id from one phone is meaningless (and
 *    potentially actively wrong) on another.
 *  - [AppSettings.chatSessions]/dashboard cache/discovery records -- conversation history and
 *    derived/cached data, not "Einstellungen"; restoring old chat history on top of whatever the
 *    target device already has would be a surprising, unrequested side effect of a settings
 *    import.
 * Every field type here is already `@Serializable` for its own, unrelated reasons (persisted as
 * its own DataStore JSON blob) -- see [McpServerConfig], [AuthMethod], [LlmProviderType],
 * [AppLanguage], [DailyLlmUsage].
 */
@Serializable
data class SettingsBackup(
    val schemaVersion: Int = SETTINGS_BACKUP_SCHEMA_VERSION,
    val userName: String = "",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val nightscoutApiUrl: String = "",
    val nightscoutApiSecret: String = "",
    val nightscoutApiAuthMethod: AuthMethod = AuthMethod.API_SECRET_HEADER,
    val nightscoutApiEnabled: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val additionalInstructions: String = "",
    val licenseKey: String = "",
    val llmProviderType: LlmProviderType = LlmProviderType.LOCAL,
    val geminiApiKey: String = "",
    val claudeApiKey: String = "",
    val claudeBaseUrl: String = DEFAULT_CLAUDE_BASE_URL,
    val openAiApiKey: String = "",
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val geminiModel: String = AUTO_MODEL_ID,
    val claudeModel: String = AUTO_MODEL_ID,
    val openAiModel: String = AUTO_MODEL_ID,
    val deepseekApiKey: String = "",
    val deepseekModel: String = AUTO_MODEL_ID,
    val oneProviderApiKey: String = "",
    val oneProviderModel: String = AUTO_MODEL_ID,
    val appLanguage: AppLanguage = AppLanguage.GERMAN,
    val userRole: UserRole = UserRole.DIABETIKER,
    val llmUsage: List<DailyLlmUsage> = emptyList(),
)

fun AppSettings.toBackup(): SettingsBackup = SettingsBackup(
    userName = userName,
    mcpServers = mcpServers,
    nightscoutApiUrl = nightscoutApiUrl,
    nightscoutApiSecret = nightscoutApiSecret,
    nightscoutApiAuthMethod = nightscoutApiAuthMethod,
    nightscoutApiEnabled = nightscoutApiEnabled,
    systemPrompt = systemPrompt,
    additionalInstructions = additionalInstructions,
    licenseKey = licenseKey,
    llmProviderType = llmProviderType,
    geminiApiKey = geminiApiKey,
    claudeApiKey = claudeApiKey,
    claudeBaseUrl = claudeBaseUrl,
    openAiApiKey = openAiApiKey,
    openAiBaseUrl = openAiBaseUrl,
    geminiModel = geminiModel,
    claudeModel = claudeModel,
    openAiModel = openAiModel,
    deepseekApiKey = deepseekApiKey,
    deepseekModel = deepseekModel,
    oneProviderApiKey = oneProviderApiKey,
    oneProviderModel = oneProviderModel,
    appLanguage = appLanguage,
    userRole = userRole,
    llmUsage = llmUsage,
)

private val settingsBackupJson = Json { ignoreUnknownKeys = true }

fun SettingsBackup.encodeToJson(): String = settingsBackupJson.encodeToString(this)

/** Throws on malformed JSON -- deliberately not the usual "runCatching -> emptyList/null" fallback
 * pattern used elsewhere in this file's sibling `decodeXxx` functions (a broken chat-session/
 * discovery-record blob degrading to "nothing there" is harmless; a broken *import* silently
 * becoming a blank [SettingsBackup] would instead silently wipe out the user's real settings on
 * apply). Callers (see [com.example.diabai.ui.SettingsViewModel.applyImportResult]) must catch
 * this and surface a clear "Ungültiges Backup-Format" error instead of applying anything. */
fun String.decodeSettingsBackup(): SettingsBackup = settingsBackupJson.decodeFromString(this)
