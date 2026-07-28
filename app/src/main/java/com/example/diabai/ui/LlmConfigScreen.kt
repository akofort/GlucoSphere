package com.example.diabai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.diabai.data.DEFAULT_CLAUDE_BASE_URL
import com.example.diabai.data.DEFAULT_OPENAI_BASE_URL
import com.example.diabai.data.DownloadProgress
import com.example.diabai.data.LlmProviderType
import com.example.diabai.data.ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT
import com.example.diabai.data.ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE
import com.example.diabai.data.ONEPROVIDER_GATEWAY_BASE_URL
import com.example.diabai.domain.EngineState
import com.example.diabai.domain.llm.AUTO_MODEL_ID
import com.example.diabai.domain.llm.CUSTOM_MODEL_ID_REGEX
import com.example.diabai.domain.llm.ModelCatalog
import java.io.File

/** Ollama's OpenAI-Chat-Completions-compatible endpoint (`/v1` under its default local port) --
 * same request/response shape [com.example.diabai.domain.llm.OpenAiApiProvider] already speaks
 * for OpenAI/OpenRouter, just pointed at a local, typically-unauthenticated server instead. */
private const val OLLAMA_BASE_URL = "http://localhost:11434/v1"
private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

/** One Base-URL preset chip: tapping it fills in [baseUrl] instead of the user having to know/type
 * it. Which presets are offered depends on [type] -- see [baseUrlPresetsFor] -- since a Claude
 * gateway and an OpenAI-compatible one are never interchangeable. */
private data class BaseUrlPreset(val label: String, val baseUrl: String)

/** [LlmProviderType.OPENAI]'s [OPENAI]/[OPENROUTER] both need a real API key exactly as before;
 * [OLLAMA] typically needs none (a local, unauthenticated server) -- the API-Key field stays
 * required by the existing "Testen"-gate either way, but any placeholder value works since Ollama
 * simply ignores an Authorization header it wasn't expecting. [LlmProviderType.CLAUDE] offers
 * Anthropic directly, or an Anthropic-compatible gateway (e.g. "OneProvider") that speaks the
 * exact same wire format under a different host/key. */
private fun baseUrlPresetsFor(type: LlmProviderType): List<BaseUrlPreset> = when (type) {
    LlmProviderType.OPENAI -> listOf(
        BaseUrlPreset("OpenAI", DEFAULT_OPENAI_BASE_URL),
        BaseUrlPreset("OpenRouter", OPENROUTER_BASE_URL),
        BaseUrlPreset("Ollama (lokal)", OLLAMA_BASE_URL),
    )
    LlmProviderType.CLAUDE -> listOf(
        BaseUrlPreset("Anthropic (offiziell)", DEFAULT_CLAUDE_BASE_URL),
        BaseUrlPreset("OneProvider Gateway", ONEPROVIDER_GATEWAY_BASE_URL),
    )
    else -> emptyList()
}

/** The sensible "nothing typed yet" fallback for [ProviderKeyForm]'s Base-URL field, per [type] --
 * mirrors what [com.example.diabai.domain.llm.LLMProviderManager.resolve] itself falls back to
 * for an empty/blank saved value. */
private fun defaultBaseUrlFor(type: LlmProviderType): String = when (type) {
    LlmProviderType.CLAUDE -> DEFAULT_CLAUDE_BASE_URL
    else -> DEFAULT_OPENAI_BASE_URL
}

/** Localized display label for [type] -- UI-only, does NOT touch [LlmProviderType.label] itself
 * (still German, used for non-UI purposes like Performance-Log rows/share text). */
private fun providerLabel(type: LlmProviderType, strings: Strings): String = when (type) {
    LlmProviderType.LOCAL -> strings.llmProviderLocal
    LlmProviderType.GEMINI -> strings.llmProviderGemini
    LlmProviderType.CLAUDE -> strings.llmProviderClaude
    LlmProviderType.OPENAI -> strings.llmProviderOpenAi
    LlmProviderType.DEEPSEEK -> strings.llmProviderDeepSeek
    LlmProviderType.ONEPROVIDER_FREE -> strings.llmProviderOneProvider
}

/** Sub-menu: pick which LLM backend answers requests (local Gemma or a cloud provider), manage
 * the Gemma (.litertlm) model file, and configure/test cloud API keys. */
@Composable
fun LlmConfigScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val llmProviderTestState by viewModel.llmProviderTestState.collectAsState()
    val customModelTestState by viewModel.customModelTestState.collectAsState()

    var downloadUrl by rememberSaveable(settings.modelDownloadUrl) { mutableStateOf(settings.modelDownloadUrl) }

    val filePicker = rememberFilePickerLauncher { uri -> viewModel.importModel(uri) }
    val isBusy = isImporting || downloadProgress != null || engineState == EngineState.LoadingModel

    SettingsScaffold(title = strings.llmConfigMenuTitle, onBack = onBack, modifier = modifier) {
        LlmProviderSection(
            activeType = settings.llmProviderType,
            geminiApiKey = settings.geminiApiKey,
            claudeApiKey = settings.claudeApiKey,
            claudeBaseUrl = settings.claudeBaseUrl,
            openAiApiKey = settings.openAiApiKey,
            openAiBaseUrl = settings.openAiBaseUrl,
            geminiModel = settings.geminiModel,
            claudeModel = settings.claudeModel,
            openAiModel = settings.openAiModel,
            deepseekApiKey = settings.deepseekApiKey,
            deepseekModel = settings.deepseekModel,
            oneProviderApiKey = settings.oneProviderApiKey,
            oneProviderModel = settings.oneProviderModel,
            oneProviderFreeRequestsToday = settings.oneProviderFreeRequestsToday,
            testState = llmProviderTestState,
            customModelTestState = customModelTestState,
            onSelectLocal = viewModel::selectLocalProvider,
            onSelectOneProviderFree = viewModel::selectOneProviderFree,
            onTest = viewModel::testLlmProviderKey,
            onSave = viewModel::saveLlmProvider,
            onSelectModel = viewModel::saveLlmModel,
            onTestCustomModel = viewModel::testCustomModel,
        ) {
            // Only rendered while "Lokales Modell" is the form's currently selected radio option
            // -- see LlmProviderSection's `when (formType)` -- so the Gemma download/import UI
            // doesn't clutter the screen while a cloud provider is what's actually being configured.
            Text(strings.llmGemmaTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                strings.llmGemmaSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.modelFilePath.isBlank()) {
                Text(strings.llmNoModelSelected, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(settings.modelFilePath, style = MaterialTheme.typography.bodySmall)
                val file = remember(settings.modelFilePath) { File(settings.modelFilePath) }
                if (file.exists()) {
                    Text(
                        text = strings.llmSizeOnDevice("%.2f".format(file.length() / 1_000_000_000.0)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val currentDownloadProgress = downloadProgress
            val currentEngineState = engineState
            StatusLine(
                text = when {
                    isImporting -> strings.llmImportingFile
                    currentDownloadProgress != null -> currentDownloadProgress.describe()
                    else -> when (currentEngineState) {
                        EngineState.Idle -> strings.llmEngineIdle
                        EngineState.LoadingModel -> strings.llmEngineLoadingStatus
                        is EngineState.Ready -> strings.llmEngineReady
                        EngineState.Generating -> strings.llmEngineGenerating
                        is EngineState.Error -> strings.llmEngineErrorStatus(currentEngineState.message)
                    }
                },
                isBusy = isBusy,
                tone = when {
                    currentEngineState is EngineState.Error -> StatusTone.ERROR
                    currentEngineState is EngineState.Ready -> StatusTone.SUCCESS
                    else -> StatusTone.NEUTRAL
                },
            )
            if (currentDownloadProgress != null) {
                val fraction = currentDownloadProgress.totalBytes?.let { total ->
                    if (total > 0) (currentDownloadProgress.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null
                }
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            importError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Column {
                Text(strings.llmDownloadDirectTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = downloadUrl,
                    onValueChange = { downloadUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.llmDownloadUrlLabel) },
                    singleLine = true,
                    enabled = !isBusy,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.downloadModel(downloadUrl.trim()) }, enabled = !isBusy && downloadUrl.isNotBlank()) {
                    Text(strings.llmDownloadButton)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.llmDownloadHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column {
                Text(strings.llmManualSelectTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = filePicker, enabled = !isBusy) { Text(strings.llmPickFileButton) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = viewModel::clearModel, enabled = settings.modelFilePath.isNotBlank() && !isBusy) {
                        Text(strings.genericRemove)
                    }
                }
                Text(
                    text = strings.llmPickFileHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun DownloadProgress.describe(): String {
    val downloadedMb = bytesDownloaded / 1_000_000.0
    val total = totalBytes
    return if (total != null && total > 0) {
        "Download: %.0f / %.0f MB".format(downloadedMb, total / 1_000_000.0)
    } else {
        "Download: %.0f MB".format(downloadedMb)
    }
}

/** Mode selector (radio buttons) plus, for whichever cloud provider is currently selected in
 * the form, a maskable API-key field (+ Base-URL for Claude/OpenAI, both overridable to a
 * compatible gateway -- see [baseUrlPresetsFor]) with its own "Key testen" gate before "Speichern"
 * is enabled -- mirrors the MCP/Nightscout test-then-save pattern elsewhere in this screen. */
@Composable
private fun LlmProviderSection(
    activeType: LlmProviderType,
    geminiApiKey: String,
    claudeApiKey: String,
    claudeBaseUrl: String,
    openAiApiKey: String,
    openAiBaseUrl: String,
    geminiModel: String,
    claudeModel: String,
    openAiModel: String,
    deepseekApiKey: String,
    deepseekModel: String,
    oneProviderApiKey: String,
    oneProviderModel: String,
    oneProviderFreeRequestsToday: Int,
    testState: ConnectionTestState,
    customModelTestState: ConnectionTestState,
    onSelectLocal: () -> Unit,
    onSelectOneProviderFree: () -> Unit,
    onTest: (LlmProviderType, String, String) -> Unit,
    onSave: (LlmProviderType, String, String) -> Unit,
    onSelectModel: (LlmProviderType, String) -> Unit,
    onTestCustomModel: (LlmProviderType, String, String, String) -> Unit,
    /** Gemma/LiteRT download-and-import UI -- only shown while "Lokales Modell" is the form's
     * selected radio option (see the `when (formType)` below), per "'Lokales Modell (LiteRT)'
     * soll nur dann aufklappbar/konfigurierbar sein, wenn 'Lokales Modell' explizit als aktiver
     * Modus ausgewählt wurde". */
    localModelContent: @Composable () -> Unit,
) {
    val strings = LocalStrings.current
    var formType by remember(activeType) { mutableStateOf(activeType) }

    Column {
        Text(strings.llmProviderSectionTitle, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = strings.llmActiveLabel(providerLabel(activeType, strings)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LlmProviderType.entries.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = formType == type,
                    onClick = {
                        formType = type
                        // Both need no "Testen"/"Speichern" gate -- LOCAL has no credentials at
                        // all, and ONEPROVIDER_FREE uses the app-embedded key automatically, so
                        // tapping either radio option persists the choice immediately.
                        if (type == LlmProviderType.LOCAL) onSelectLocal()
                        if (type == LlmProviderType.ONEPROVIDER_FREE) onSelectOneProviderFree()
                    },
                )
                Text(providerLabel(type, strings), style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (formType) {
            LlmProviderType.LOCAL -> {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                localModelContent()
            }
            LlmProviderType.GEMINI -> ProviderKeyForm(
                type = LlmProviderType.GEMINI,
                initialApiKey = geminiApiKey,
                showBaseUrl = false,
                initialBaseUrl = "",
                selectedModel = geminiModel,
                testState = testState,
                customModelTestState = customModelTestState,
                onTest = onTest,
                onSave = onSave,
                onSelectModel = onSelectModel,
                onTestCustomModel = onTestCustomModel,
            )
            LlmProviderType.CLAUDE -> ProviderKeyForm(
                type = LlmProviderType.CLAUDE,
                initialApiKey = claudeApiKey,
                showBaseUrl = true,
                initialBaseUrl = claudeBaseUrl.ifBlank { DEFAULT_CLAUDE_BASE_URL },
                selectedModel = claudeModel,
                testState = testState,
                customModelTestState = customModelTestState,
                onTest = onTest,
                onSave = onSave,
                onSelectModel = onSelectModel,
                onTestCustomModel = onTestCustomModel,
            )
            LlmProviderType.OPENAI -> ProviderKeyForm(
                type = LlmProviderType.OPENAI,
                initialApiKey = openAiApiKey,
                showBaseUrl = true,
                initialBaseUrl = openAiBaseUrl.ifBlank { DEFAULT_OPENAI_BASE_URL },
                selectedModel = openAiModel,
                testState = testState,
                customModelTestState = customModelTestState,
                onTest = onTest,
                onSave = onSave,
                onSelectModel = onSelectModel,
                onTestCustomModel = onTestCustomModel,
            )
            LlmProviderType.DEEPSEEK -> ProviderKeyForm(
                type = LlmProviderType.DEEPSEEK,
                initialApiKey = deepseekApiKey,
                showBaseUrl = false,
                initialBaseUrl = "",
                selectedModel = deepseekModel,
                testState = testState,
                customModelTestState = customModelTestState,
                onTest = onTest,
                onSave = onSave,
                onSelectModel = onSelectModel,
                onTestCustomModel = onTestCustomModel,
            )
            LlmProviderType.ONEPROVIDER_FREE -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.llmOneProviderExplain(ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderKeyForm(
                    type = LlmProviderType.ONEPROVIDER_FREE,
                    initialApiKey = oneProviderApiKey,
                    showBaseUrl = false,
                    initialBaseUrl = "",
                    selectedModel = oneProviderModel,
                    keyOptional = true,
                    testState = testState,
                    customModelTestState = customModelTestState,
                    onTest = onTest,
                    onSave = onSave,
                    onSelectModel = onSelectModel,
                    onTestCustomModel = onTestCustomModel,
                )
                Spacer(Modifier.height(8.dp))
                val remaining = (ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT - oneProviderFreeRequestsToday).coerceAtLeast(0)
                StatusLine(
                    text = if (oneProviderApiKey.isNotBlank()) {
                        strings.llmOwnKeyActive
                    } else if (remaining > 0) {
                        strings.llmFreeRequestsRemaining(remaining, ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT)
                    } else {
                        ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE
                    },
                    isBusy = false,
                    tone = if (oneProviderApiKey.isNotBlank() || remaining > 0) StatusTone.SUCCESS else StatusTone.ERROR,
                )
            }
        }
    }
}

@Composable
private fun ProviderKeyForm(
    type: LlmProviderType,
    initialApiKey: String,
    showBaseUrl: Boolean,
    initialBaseUrl: String,
    selectedModel: String,
    testState: ConnectionTestState,
    customModelTestState: ConnectionTestState,
    onTest: (LlmProviderType, String, String) -> Unit,
    onSave: (LlmProviderType, String, String) -> Unit,
    onSelectModel: (LlmProviderType, String) -> Unit,
    onTestCustomModel: (LlmProviderType, String, String, String) -> Unit,
    /** [LlmProviderType.ONEPROVIDER_FREE] only: a blank key is itself a valid, immediately
     * saveable choice (falls back to the app-embedded free-tier key), unlike every other provider
     * here where a blank key means "nothing configured yet" -- see the two `canSave`/"Testen"
     * conditions below. */
    keyOptional: Boolean = false,
) {
    val strings = LocalStrings.current
    var apiKey by remember(type, initialApiKey) { mutableStateOf(initialApiKey) }
    var baseUrl by remember(type, initialBaseUrl) { mutableStateOf(initialBaseUrl) }

    val effectiveBaseUrl = if (showBaseUrl) baseUrl.trim().ifBlank { defaultBaseUrlFor(type) } else ""
    val isTesting = testState is ConnectionTestState.Testing
    val currentKey = llmProviderTestKey(type, apiKey.trim(), effectiveBaseUrl)
    val testMatchesCurrent = (testState as? ConnectionTestState.Finished)?.testedTarget == currentKey
    // A blank key is always saveable without testing, for every provider (not just the
    // keyOptional ones) -- there's nothing to test against, and this is the only way to actually
    // clear/remove a previously saved key: without this, once ANY key had been saved, the
    // "Testen"-gate below (disabled while blank, for a provider that isn't keyOptional) made it
    // impossible to ever get back to "no key saved" again.
    val canSave = apiKey.isBlank() || (testMatchesCurrent && testState is ConnectionTestState.Success)

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        PasswordField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = if (keyOptional) strings.apiKeyOptionalLabel else strings.apiKeyLabel,
            enabled = !isTesting,
        )
        Spacer(Modifier.height(12.dp))
        ModelSelector(
            type = type,
            selected = selectedModel,
            onSelect = onSelectModel,
            apiKey = apiKey.trim(),
            baseUrl = effectiveBaseUrl,
            customModelTestState = customModelTestState,
            onTestCustomModel = onTestCustomModel,
        )
        if (showBaseUrl) {
            Spacer(Modifier.height(8.dp))
            Text(strings.llmProviderPresetTitle, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                baseUrlPresetsFor(type).forEach { preset ->
                    FilterChip(
                        selected = baseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/'),
                        onClick = { baseUrl = preset.baseUrl },
                        enabled = !isTesting,
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (type == LlmProviderType.OPENAI) {
                Text(
                    text = strings.llmOllamaHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            } else if (type == LlmProviderType.CLAUDE) {
                Text(
                    text = strings.llmOneProviderGatewayHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.baseUrlLabel) },
                placeholder = { Text(defaultBaseUrlFor(type)) },
                singleLine = true,
                enabled = !isTesting,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(
                onClick = { onTest(type, apiKey.trim(), effectiveBaseUrl) },
                enabled = !isTesting && (apiKey.isNotBlank() || keyOptional),
            ) {
                Text(strings.genericTest)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSave(type, apiKey.trim(), effectiveBaseUrl) }, enabled = canSave) {
                Text(strings.genericSave)
            }
        }
        Spacer(Modifier.height(4.dp))
        StatusLine(
            text = when {
                isTesting -> strings.llmKeyBeingChecked
                testState is ConnectionTestState.Success && testMatchesCurrent -> strings.llmKeyValid
                testState is ConnectionTestState.Error && testMatchesCurrent -> strings.genericErrorPrefix(testState.message)
                keyOptional && apiKey.isBlank() -> strings.llmKeyOptionalBlankHint
                apiKey.isBlank() -> strings.llmKeyBlankHint
                else -> strings.llmNotTestedBeforeSave
            },
            isBusy = isTesting,
            tone = when {
                testState is ConnectionTestState.Success && testMatchesCurrent -> StatusTone.SUCCESS
                testState is ConnectionTestState.Error && testMatchesCurrent -> StatusTone.ERROR
                else -> StatusTone.NEUTRAL
            },
        )
    }
}

/** Model choice for one cloud provider -- persisted immediately on tap for a catalog entry
 * (unlike the API key form above, this never needs a "testen" gate on its own: it only changes
 * which model id future requests use, nothing about connectivity). "Automatisch" is the
 * recommended default: [ModelCatalog.resolve] picks the fast model for chat and the flagship for
 * Übersicht/report analysis. "Benutzerdefiniertes Modell eingeben" is the one exception -- a
 * manually typed id *does* need its own "Testen" gate before it can be selected, since (unlike a
 * curated catalog entry) there's no guarantee it's even a real, reachable model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSelector(
    type: LlmProviderType,
    selected: String,
    onSelect: (LlmProviderType, String) -> Unit,
    apiKey: String,
    baseUrl: String,
    customModelTestState: ConnectionTestState,
    onTestCustomModel: (LlmProviderType, String, String, String) -> Unit,
) {
    val strings = LocalStrings.current
    val catalogIds = remember(type) { ModelCatalog.optionsFor(type).map { it.id }.toSet() + AUTO_MODEL_ID }
    val isCustomSelected = selected !in catalogIds
    var showCustomField by rememberSaveable(type) { mutableStateOf(isCustomSelected) }
    var customModelText by remember(type) { mutableStateOf(if (isCustomSelected) selected else "") }

    Column {
        Text(strings.llmModelSectionTitle, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == AUTO_MODEL_ID,
                onClick = { showCustomField = false; onSelect(type, AUTO_MODEL_ID) },
                label = { Text(strings.llmModelAuto) },
            )
            ModelCatalog.optionsFor(type).forEach { option ->
                FilterChip(
                    selected = selected == option.id,
                    onClick = { showCustomField = false; onSelect(type, option.id) },
                    label = { Text("${option.label} ${option.priceTier}") },
                )
            }
            FilterChip(
                selected = showCustomField,
                onClick = { showCustomField = true },
                label = { Text(strings.llmModelCustomChip) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = strings.llmModelAutoHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (showCustomField) {
            Spacer(Modifier.height(8.dp))
            val trimmedCustom = customModelText.trim()
            val isValidFormat = trimmedCustom.isBlank() || CUSTOM_MODEL_ID_REGEX.matches(trimmedCustom)
            val isTestingCustom = customModelTestState is ConnectionTestState.Testing
            val customKey = customModelTestKey(type, apiKey, baseUrl, trimmedCustom)
            val customTestMatchesCurrent = (customModelTestState as? ConnectionTestState.Finished)?.testedTarget == customKey
            val customTestPassed = customTestMatchesCurrent && customModelTestState is ConnectionTestState.Success

            OutlinedTextField(
                value = customModelText,
                onValueChange = { customModelText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.llmModelIdLabel) },
                placeholder = { Text("provider/model-name") },
                singleLine = true,
                isError = !isValidFormat,
                enabled = !isTestingCustom,
            )
            if (!isValidFormat) {
                Text(
                    text = strings.llmModelIdInvalidFormat,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { onTestCustomModel(type, apiKey, baseUrl, trimmedCustom) },
                    enabled = !isTestingCustom && isValidFormat && trimmedCustom.isNotBlank() && apiKey.isNotBlank(),
                ) {
                    Text(strings.genericTest)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSelect(type, trimmedCustom) },
                    enabled = customTestPassed,
                ) {
                    Text(strings.genericApply)
                }
            }
            Spacer(Modifier.height(4.dp))
            StatusLine(
                text = when {
                    isTestingCustom -> strings.llmModelBeingTested
                    customModelTestState is ConnectionTestState.Success && customTestMatchesCurrent -> strings.llmModelReachable
                    customModelTestState is ConnectionTestState.Error && customTestMatchesCurrent ->
                        strings.genericErrorPrefix(customModelTestState.message)
                    else -> strings.llmNotTestedBeforeApply
                },
                isBusy = isTestingCustom,
                tone = when {
                    customModelTestState is ConnectionTestState.Success && customTestMatchesCurrent -> StatusTone.SUCCESS
                    customModelTestState is ConnectionTestState.Error && customTestMatchesCurrent -> StatusTone.ERROR
                    else -> StatusTone.NEUTRAL
                },
            )
        }
    }
}
