package com.example.diabai.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.diabai.data.AppLanguage

/**
 * In-app translation layer for this project's most-frequently-seen screens. This project has no
 * `strings.xml` -- every UI string started life as a hardcoded German Kotlin literal directly in
 * ~20 Composable files. Earlier this drove translation through Android's system locale
 * (`LocaleManager.applicationLocales`), which turned out to be the actual crash cause: changing
 * the per-app locale triggers a full `MainActivity` recreation, which re-ran `onCreate`'s
 * local-model load a second time on a brand-new `LiteRtInferenceEngine` instance without ever
 * releasing the first one's native GPU/model resources -- two live native engines at once,
 * surfacing as a crash on the next inference ("wer bist du?"), not immediately at the toggle
 * itself. Switching to a plain Compose [androidx.compose.runtime.CompositionLocal] instead means
 * a language change is just a recomposition: instant, no Activity recreation, nothing native
 * touched twice. See `MainActivity.kt`'s `GlucoSphereApp` for where [LocalStrings] is provided.
 *
 * Scope: every screen's static UI chrome (titles, labels, buttons, hints, dialogs) is bilingual,
 * plus [com.example.diabai.domain.analytics.TrafficLightStatus.label] and
 * [com.example.diabai.domain.analytics.DashboardTimeRange.displayLabel] (own localized functions,
 * not part of this class). Deliberately NOT translated (stays German-only, matching the German
 * text an LLM produces regardless of UI language anyway):
 *  - [com.example.diabai.data.DEFAULT_SYSTEM_PROMPT] and the role-specific prompt blocks in
 *    [com.example.diabai.data.rolePromptFor] -- instructions sent TO the model, not UI text.
 *  - Live connection-test/error strings built in [SettingsViewModel] (`describeConnectionError`/
 *    `describeHttpStatus`/`describeMcpErrorCode`, OAuth2 discovery/login log lines) and raw
 *    provider/MCP error bodies threaded through from the network layer -- these come from,
 *    or are built alongside, business logic that isn't itself language-aware; translating them
 *    would mean threading [AppLanguage] through every connection-test call site, a separate,
 *    larger follow-up.
 *
 * Implementation note: this used to be one flat `data class Strings(...)` with ~320 constructor
 * parameters. That triggered a real ART/D8 dex bug -- `StringsKt.<clinit>` failed the bytecode
 * verifier at runtime ("Rejecting invocation, expected 68 argument registers, method signature
 * has 69 or more") because the single constructor-invoke instruction building [StringsDe]/
 * [StringsEn] carried too many argument registers. Splitting the fields into ~15 small
 * per-screen interfaces, implemented as separate anonymous objects and composed onto [Strings]
 * via interface delegation (`by`), keeps every individual constructor/object-literal call small;
 * call sites are unaffected since `strings.someField` still resolves the same way through
 * delegation.
 */
internal interface MainTabsScreenStrings {
    val tabOverview: String
    val tabChat: String
    val deleteHistoryTitle: String
    val deleteHistoryText: String
    val deleteHistoryConfirm: String
    val deleteHistoryDismiss: String
    val deleteHistoryContentDescription: String
    val settingsContentDescription: String
}

internal interface DashboardSectionStrings {
    val overviewTitle: String
    val refresh: String
    val editFilters: String
    val collapseFilters: String
    val dataSources: String
    val singleSelectHint: String
    val noDataSuffix: String
    val evaluatedPeriod: String
    val comparisonPeriod: String
    val currentValueLabel: String
    val computingComparisonPeriod: String
    val noDashboardDataTitle: String
    val noDashboardDataHint: String
    val timeInRange: String
    val metricsTitle: String
    val metricTir: String
    val metricHypo: String
    val metricSevereHypo: String
    val metricHyper: String
    val metricVariability: String
    val metricAvgGlucose: String
    val metricHbA1c: String
    val bodyCompositionTitle: String
    val bodyWeight: String
    val bodyFat: String
    val bodyTrend3Month: String
    val trendUp: String
    val trendDown: String
    val trendStable: String
    val trendUnknown: String
    val trendVsPrevious: String
    val summaryTitle: String
    val tipsTitle: String
    val applyRange: String
    val allSources: String
    val dateFrom: (String) -> String
    val dateTo: (String) -> String
    val lastUpdated: (String) -> String
}

internal interface ChatSectionStrings {
    val includeMcpServers: String
    val chatPlaceholder: String
    val send: String
    val cancel: String
    val hideReasoning: String
    val showReasoning: (tokenEstimate: Int) -> String
    val listening: String
    val micPrompt: String
    val messageCopied: String
    val aiThinking: String
    val aiCallingTool: (serverName: String) -> String
    val aiGenerating: String
    val copyAction: String
    val shareAction: String
    val sharePdfAction: String
    val shareContentDescription: String
    val voiceModeListening: String
    val voiceModeSpeaking: String
    val voiceModeIdle: String
    val voiceModeExit: String
    val activeToolsLabel: String
    val youLabel: String
    val mcpDisabledLabel: String
    val shareChatContentDescription: String
}

internal interface SettingsOverviewScreenStrings {
    val settingsTitle: String
    val languageLabel: String
    val languageHint: String
    val appearanceSectionTitle: String
    val llmConfigMenuTitle: String
    val dataSourcesMenuTitle: String
    val systemPromptMenuTitle: String
    val aboutMenuTitle: String
    val aboutMenuSubtitle: String
    val backupMenuTitle: String
    val backupMenuSubtitle: String
    val performanceLogMenuTitle: String
    val performanceLogMenuSubtitle: String
    val helpMenuTitle: String
    val helpMenuSubtitle: String
}

internal interface SettingsOverviewSubtitlesStrings {
    val settingsDownloadRunning: String
    val settingsLocalModelActive: (String) -> String
    val settingsLoadError: String
    val settingsLoadingModel: String
    val settingsNoModelConfigured: String
    val settingsApiActive: (String) -> String
    val settingsOneProviderFreeActive: (Int, Int) -> String
    val settingsPureChatModeSubtitle: String
    val settingsMcpConnected: (Int, Int) -> String
    val settingsConfiguredNotConnected: String
    val settingsPromptStandard: String
    val settingsPromptCustomized: String
}

internal interface GenericStrings {
    val genericBack: String
    val genericSave: String
    val genericTest: String
    val genericRemove: String
    val genericYes: String
    val genericNo: String
    val sourceChoiceQuestion: (List<String>) -> String
    val sourceChoiceAllOption: String
    val genericClose: String
    val genericApply: String
    val genericEnabled: String
    val genericDisabled: String
    val genericErrorPrefix: (String) -> String
}

internal interface LlmConfigScreenStrings {
    val llmProviderLocal: String
    val llmProviderGemini: String
    val llmProviderClaude: String
    val llmProviderOpenAi: String
    val llmProviderDeepSeek: String
    val llmProviderOneProvider: String
    val llmGemmaTitle: String
    val llmGemmaSubtitle: String
    val llmNoModelSelected: String
    val llmSizeOnDevice: (String) -> String
    val llmImportingFile: String
    val llmEngineIdle: String
    val llmEngineLoadingStatus: String
    val llmEngineReady: String
    val llmEngineGenerating: String
    val llmEngineErrorStatus: (String) -> String
    val llmDownloadDirectTitle: String
    val llmDownloadUrlLabel: String
    val llmDownloadButton: String
    val llmDownloadHint: String
    val llmManualSelectTitle: String
    val llmPickFileButton: String
    val llmPickFileHint: String
    val llmActiveLabel: (String) -> String
    val llmProviderSectionTitle: String
    val llmProviderPresetTitle: String
    val llmOllamaHint: String
    val llmOneProviderGatewayHint: String
    val baseUrlLabel: String
    val apiKeyLabel: String
    val apiKeyOptionalLabel: String
    val llmOneProviderExplain: (Int) -> String
    val llmOwnKeyActive: String
    val llmFreeRequestsRemaining: (Int, Int) -> String
    val llmKeyBeingChecked: String
    val llmKeyValid: String
    val llmKeyOptionalBlankHint: String
    val llmKeyBlankHint: String
    val llmNotTestedBeforeSave: String
    val llmModelSectionTitle: String
    val llmModelAuto: String
    val llmModelCustomChip: String
    val llmModelAutoHint: String
    val llmModelIdLabel: String
    val llmModelIdInvalidFormat: String
    val llmModelBeingTested: String
    val llmModelReachable: String
    val llmNotTestedBeforeApply: String
}

internal interface DataSourcesScreenStrings {
    val dsPureChatModeHint: String
    val dsMcpServersTitle: (Int) -> String
    val dsAddMcpServer: String
    val dsAuthTypeLabel: String
    val authNone: String
    val authBearer: String
    val authCustomHeader: String
    val authOAuth2: String
    val dsTransportLabel: String
    val dsDataCategoryLabel: String
    val dsToolsCount: (Int) -> String
    val dsNameLabel: String
    val dsNamePlaceholder: String
    val dsRestApiHint: String
    val dsUrlLabel: String
    val dsTokenSecretLabel: String
    val dsDataSourceEnabled: String
    val dsQueriedByDashboardChat: String
    val dsEnableAfterTestHint: String
    val dsDiscoveryTitle: String
    val dsDiscoveryHint: String
    val dsExploreButton: String
    val dsAutoRunToolsTitle: String
    val dsAutoRunToolsHint: String
    val dsToolsTitle: (Int) -> String
    val dsToolsAutoApproveHint: String
    val dsToolsLoading: String
    val dsTestConnection: String
    val dsTestingConnection: String
    val dsConnectionSuccessful: String
    val dsNotTestedBeforeEnable: String
    val dsSavedNotConnected: String
    val dsConnecting: String
    val dsConnected: String
    val dsConnectionFailed: (String) -> String
    val dsHideConnectionLog: String
    val dsShowConnectionLog: String
    val dsCopyLog: String
    val dsOAuth2Hint: String
    val dsLoginAgain: String
    val dsLoginWithProvider: String
    val dsOAuth2Discovering: String
    val dsOAuth2LoggingIn: String
    val dsLoggedIn: String
    val dsNotLoggedIn: String
    val dsHideManualEndpoints: String
    val dsShowManualEndpoints: String
    val dsAuthEndpointLabel: String
    val dsTokenEndpointLabel: String
    val dsClientIdLabel: String
    val dsScopeOptionalLabel: String
    val dsNightscoutTitle: String
    val dsNightscoutHint: String
    val dsNightscoutUrlLabel: String
    val dsNightscoutDiscoveryHint: String
    val dsNightscoutRestApiName: String
    val dsDiscoverySheetExploring: String
    val dsDiscoveryNotExploredYet: String
    val dsAvailableToolsTitle: String
    val dsNoToolsFound: String
    val dsWhatYouCanAskTitle: String
    val dsDataStructureTitle: String
    val dsReadOnly: String
    val dsWriteCapable: String
    val dsWriteCapableSuffix: String
    val dsKeyReturnValues: (String) -> String
    val dsResources: (String) -> String
    val dsExploreNow: String
    val dsExploreAgain: String
}

internal interface SystemPromptScreenStrings {
    val spPersonalAddressTitle: String
    val spNameLabel: String
    val spNamePlaceholder: String
    val spNameHint: String
    val spSaveName: String
    val spBasePromptDevHint: String
    val spSystemPromptLabel: String
    val spMatchesDefault: String
    val spUnsavedChanges: String
    val spResetToDefault: String
    val spAdditionalInstructionsTitle: String
    val spInstructionsTitle: String
    val spAdditionalDevHint: String
    val spAdditionalNonDevHint: String
    val spCollapseInstructions: String
    val spEditInstructions: String
    val spAdditionalInstructionsPlaceholder: String
}

internal interface AboutScreenStrings {
    val aboutBuildLine: (String, String) -> String
    val aboutActiveSystemLlm: String
    val aboutLicenseTitle: String
    val aboutDailyLimitReached: String
    val aboutMinutesRemaining: (Long) -> String
    val aboutLicenseKeyLabel: String
    val aboutLicenseKeyPlaceholder: String
    val aboutSaveLicenseKey: String
    val aboutUsageTitle: String
    val aboutUsageHint: String
    val aboutTodayTokens: (Long) -> String
    val aboutLast30DaysTokens: (Long) -> String
    val aboutEstimatedCost: (String) -> String
    val aboutCostDisclaimer: String
    val aboutResetUsage: String
    val aboutDisclaimerTitle: String
    val aboutOpenSourceLicenses: String
    val aboutPrivacyNotice: String
    val aboutCopyright: String
    val aboutFooterDisclaimer: String
    val aboutShowDisclaimer: String
    val aboutPrivacyDialogTitle: String
    val aboutDisclaimerText: String
    val aboutLicensesText: String
    val aboutPrivacyText: String
}

internal interface BackupScreenStrings {
    val backupExportTitle: String
    val backupExportHint: String
    val backupEncryptCheckbox: String
    val backupPasswordLabel: String
    val backupImportTitle: String
    val backupImportHint: String
    val backupImporting: String
    val backupImportSuccess: String
    val backupWrongPassword: String
    val backupPasswordRequiredTitle: String
    val backupWrongPasswordRetry: String
    val backupEncryptedFileHint: String
    val backupDecrypt: String
}

internal interface PerformanceLogScreenStrings {
    val perfHint: (Int) -> String
    val perfShareChooserTitle: String
    val perfClearLog: String
    val perfNoEntries: String
    val perfDebugLogTitle: String
    val perfDebugLogHint: (Int) -> String
    val perfShareDebugLogChooserTitle: String
    val perfNoDebugEntries: String
    val perfTokensToolCalls: (Long, Long, Int) -> String
    val perfNoEntriesShareText: String
    val perfShareTextHeader: (Int) -> String
    val perfNoDebugEntriesShareText: String
    val perfDebugShareTextHeader: (Int) -> String
}

internal interface HelpScreenStrings {
    val helpGeminiKeyTitle: String
    val helpGeminiKeySteps: String
    val helpOpenGoogleAiStudio: String
    val helpMcpSuggestionsTitle: String
    val helpMcpSuggestionsHint: String
    val helpNightscoutDesc: String
    val helpGlookoDesc: String
    val helpWithingsDesc: String
    val helpWithingsAlt1: String
    val helpWithingsAlt2: String
    val helpWithingsAlt3: String
    val helpFeelfitDesc: String
    val helpGoogleHealthDesc: String
    val helpGoogleHealthAlt: String
    val helpStravaTitle: String
    val helpStravaDesc: String
    val helpStravaSettingsLink: String
    val helpOpenGithubRepo: String
}

internal interface ProfileScreenStrings {
    val profileTitle: String
    val profileUserTypeTitle: String
    val profileHint: String
    val roleDiabetikerLabel: String
    val roleDiabetikerDesc: String
    val roleFachpersonalLabel: String
    val roleFachpersonalDesc: String
    val roleAngehorigeLabel: String
    val roleAngehorigeDesc: String
}

internal interface ViewModelChatTailStrings {
    val toolRunning: String
    val toolDeclined: String
    val toolCompleted: String
    val autoRoutedTo: (String) -> String
    val pdfAnswerTitle: String
}

class Strings internal constructor(
    mainTabsScreenStrings: MainTabsScreenStrings,
    dashboardSectionStrings: DashboardSectionStrings,
    chatSectionStrings: ChatSectionStrings,
    settingsOverviewScreenStrings: SettingsOverviewScreenStrings,
    settingsOverviewSubtitlesStrings: SettingsOverviewSubtitlesStrings,
    genericStrings: GenericStrings,
    llmConfigScreenStrings: LlmConfigScreenStrings,
    dataSourcesScreenStrings: DataSourcesScreenStrings,
    systemPromptScreenStrings: SystemPromptScreenStrings,
    aboutScreenStrings: AboutScreenStrings,
    backupScreenStrings: BackupScreenStrings,
    performanceLogScreenStrings: PerformanceLogScreenStrings,
    helpScreenStrings: HelpScreenStrings,
    profileScreenStrings: ProfileScreenStrings,
    viewModelChatTailStrings: ViewModelChatTailStrings,
) : MainTabsScreenStrings by mainTabsScreenStrings, DashboardSectionStrings by dashboardSectionStrings, ChatSectionStrings by chatSectionStrings, SettingsOverviewScreenStrings by settingsOverviewScreenStrings, SettingsOverviewSubtitlesStrings by settingsOverviewSubtitlesStrings, GenericStrings by genericStrings, LlmConfigScreenStrings by llmConfigScreenStrings, DataSourcesScreenStrings by dataSourcesScreenStrings, SystemPromptScreenStrings by systemPromptScreenStrings, AboutScreenStrings by aboutScreenStrings, BackupScreenStrings by backupScreenStrings, PerformanceLogScreenStrings by performanceLogScreenStrings, HelpScreenStrings by helpScreenStrings, ProfileScreenStrings by profileScreenStrings, ViewModelChatTailStrings by viewModelChatTailStrings

private val StringsDe = Strings(
    mainTabsScreenStrings = object : MainTabsScreenStrings {
        override val tabOverview = "Übersicht"
        override val tabChat = "Chat"
        override val deleteHistoryTitle = "Chatverlauf löschen"
        override val deleteHistoryText = "Möchtest du den gesamten Chatverlauf wirklich löschen?"
        override val deleteHistoryConfirm = "Löschen"
        override val deleteHistoryDismiss = "Abbrechen"
        override val deleteHistoryContentDescription = "Chatverlauf löschen"
        override val settingsContentDescription = "Einstellungen"
    },
    dashboardSectionStrings = object : DashboardSectionStrings {
        override val overviewTitle = "Übersicht"
        override val refresh = "Aktualisieren"
        override val editFilters = "Filter bearbeiten ▼"
        override val collapseFilters = "Filter einklappen ▲"
        override val dataSources = "Datenquellen"
        override val singleSelectHint = "Zeitraum > 5 Tage: nur eine Quelle pro Thema wählbar."
        override val noDataSuffix = " (keine Daten)"
        override val evaluatedPeriod = "Ausgewerteter Zeitraum"
        override val comparisonPeriod = "Vergleich zum Vorzeitraum"
        override val currentValueLabel = "Aktueller Wert"
        override val computingComparisonPeriod = "📊 Berechne Vergleichszeitraum …"
        override val noDashboardDataTitle = "Noch keine Daten vorhanden"
        override val noDashboardDataHint = "Die Übersicht wird nicht mehr automatisch aktualisiert. Wische zum " +
        "Aktualisieren nach unten oder tippe auf \"Aktualisieren\"."
        override val timeInRange = "Time in Range"
        override val metricsTitle = "Metriken"
        override val metricTir = "Time in Range (70-180)"
        override val metricHypo = "Hypoglykämien (<70)"
        override val metricSevereHypo = "davon schwer (<54)"
        override val metricHyper = "Hyperglykämien (>180)"
        override val metricVariability = "Variabilität (%CV)"
        override val metricAvgGlucose = "Ø Glukose"
        override val metricHbA1c = "Geschätzter HbA1c (GMI)"
        override val bodyCompositionTitle = "Körperzusammensetzung"
        override val bodyWeight = "Gewicht"
        override val bodyFat = "Körperfettanteil"
        override val bodyTrend3Month = "3-Monats-Trend"
        override val trendUp = "Steigend ↗"
        override val trendDown = "Fallend ↘"
        override val trendStable = "Stabil ➔"
        override val trendUnknown = "Nicht genug Messwerte"
        override val trendVsPrevious = "Trend vs. Vorzeitraum"
        override val summaryTitle = "Zusammenfassung"
        override val tipsTitle = "KI-Tipps"
        override val applyRange = "Anwenden"
        override val allSources = "alle Quellen"
        override val dateFrom: (String) -> String = { date -> "Von: $date" }
        override val dateTo: (String) -> String = { date -> "Bis: $date" }
        override val lastUpdated: (String) -> String = { time -> "Letzter Stand: $time" }
    },
    chatSectionStrings = object : ChatSectionStrings {
        override val includeMcpServers = "MCP-Server einbeziehen"
        override val chatPlaceholder = "Frage stellen, z. B. \"Wie lief mein Sport gestern?\""
        override val send = "Senden"
        override val cancel = "Abbrechen"
        override val hideReasoning = "💭 Denkprozess ausblenden"
        override val showReasoning: (tokenEstimate: Int) -> String = { tokens -> "💭 Denkprozess anzeigen (~$tokens Tokens)" }
        override val listening = "Höre zu …"
        override val micPrompt = "Sprechen …"
        override val messageCopied = "Nachricht kopiert"
        override val aiThinking = "💭 KI denkt nach …"
        override val aiCallingTool: (serverName: String) -> String = { serverName -> "🔧 Abfrage an $serverName …" }
        override val aiGenerating = "✍️ Formuliere Antwort …"
        override val copyAction = "Kopieren"
        override val shareAction = "Teilen"
        override val sharePdfAction = "Als PDF teilen"
        override val shareContentDescription = "Übersicht teilen"
        override val voiceModeListening = "🎙️ Höre zu …"
        override val voiceModeSpeaking = "🔊 Spricht … (antippen zum Stoppen)"
        override val voiceModeIdle = "Sprachmodus aktiv"
        override val voiceModeExit = "Beenden"
        override val activeToolsLabel = "Aktive Datenquellen"
        override val youLabel = "Du"
        override val mcpDisabledLabel = "keine (MCP deaktiviert)"
        override val shareChatContentDescription = "Chatverlauf teilen"
    },
    settingsOverviewScreenStrings = object : SettingsOverviewScreenStrings {
        override val settingsTitle = "Einstellungen"
        override val languageLabel = "Sprache"
        override val languageHint = "Steuert die Sprachein-/ausgabe (Mikrofon/Vorlesen) sowie die Ampel-Bezeichnung " +
        "(Grün/Gelb/Rot bzw. Green/Yellow/Red)."
        override val appearanceSectionTitle = "Erscheinungsbild"
        override val llmConfigMenuTitle = "LLM-Konfiguration"
        override val dataSourcesMenuTitle = "Datenquellen (MCP & API)"
        override val systemPromptMenuTitle = "System-Prompt"
        override val aboutMenuTitle = "Über GlucoSphere"
        override val aboutMenuSubtitle = "Version, Lizenzen, Datenschutz"
        override val backupMenuTitle = "Backup & Konfiguration"
        override val backupMenuSubtitle = "Einstellungen exportieren/importieren"
        override val performanceLogMenuTitle = "Performance-Log"
        override val performanceLogMenuSubtitle = "Anfragen: Anbieter, Modell, Tokens, Dauer"
        override val helpMenuTitle = "Hilfe & MCP-Anleitungen"
        override val helpMenuSubtitle = "API-Keys, MCP-Server-Links, Sicherheit"
    },
    settingsOverviewSubtitlesStrings = object : SettingsOverviewSubtitlesStrings {
        override val settingsDownloadRunning = "Download läuft …"
        override val settingsLocalModelActive: (String) -> String = { name -> "Lokales Modell aktiv: $name" }
        override val settingsLoadError = "Fehler beim Laden"
        override val settingsLoadingModel = "Wird geladen …"
        override val settingsNoModelConfigured = "Kein Modell konfiguriert"
        override val settingsApiActive: (String) -> String = { name -> "$name aktiv" }
        override val settingsOneProviderFreeActive: (Int, Int) -> String = { remaining, limit -> "OneProvider-Freikontingent aktiv ($remaining/$limit heute übrig)" }
        override val settingsPureChatModeSubtitle = "Pure Chat Mode (keine Datenquelle)"
        override val settingsMcpConnected: (Int, Int) -> String = { connected, total -> "$connected von $total MCP-Server(n) verbunden" }
        override val settingsConfiguredNotConnected = "Konfiguriert, nicht verbunden"
        override val settingsPromptStandard = "Standard"
        override val settingsPromptCustomized = "Angepasst"
    },
    genericStrings = object : GenericStrings {
        override val genericBack = "Zurück"
        override val genericSave = "Speichern"
        override val genericTest = "Testen"
        override val genericRemove = "Entfernen"
        override val genericYes = "Ja"
        override val genericNo = "Nein"
        override val sourceChoiceQuestion: (List<String>) -> String = { names ->
            "Dafür stehen mehrere Quellen zur Verfügung: ${names.joinToString(", ")}. Welche soll ich verwenden?"
        }
        override val sourceChoiceAllOption = "Alle Quellen"
        override val genericClose = "Schließen"
        override val genericApply = "Übernehmen"
        override val genericEnabled = "Aktiviert"
        override val genericDisabled = "Deaktiviert"
        override val genericErrorPrefix: (String) -> String = { msg -> "Fehler: $msg" }
    },
    llmConfigScreenStrings = object : LlmConfigScreenStrings {
        override val llmProviderLocal = "Lokales Modell (LiteRT / Gemma)"
        override val llmProviderGemini = "Google Gemini API"
        override val llmProviderClaude = "Anthropic Claude API"
        override val llmProviderOpenAi = "OpenAI API / OpenRouter"
        override val llmProviderDeepSeek = "DeepSeek API"
        override val llmProviderOneProvider = "OneProvider (eigener Key oder kostenloses Freikontingent)"
        override val llmGemmaTitle = "Gemma (LiteRT-LM, .litertlm)"
        override val llmGemmaSubtitle = "Läuft über Googles LiteRT-LM SDK auf der GPU des Geräts."
        override val llmNoModelSelected = "Kein Modell ausgewählt"
        override val llmSizeOnDevice: (String) -> String = { gb -> "$gb GB auf dem Gerät" }
        override val llmImportingFile = "Datei wird importiert …"
        override val llmEngineIdle = "Status: kein Modell geladen"
        override val llmEngineLoadingStatus = "Status: wird geladen …"
        override val llmEngineReady = "Modell einsatzbereit"
        override val llmEngineGenerating = "Modell einsatzbereit (gerade in Verwendung)"
        override val llmEngineErrorStatus: (String) -> String = { msg -> "Status: Fehler – $msg" }
        override val llmDownloadDirectTitle = "Direkt herunterladen"
        override val llmDownloadUrlLabel = "Download-URL (.litertlm)"
        override val llmDownloadButton = "Modell herunterladen (~2,4 GB)"
        override val llmDownloadHint = "Läuft über den System-Downloadmanager: Fortschritt in der Benachrichtigungsleiste, " +
        "automatischer Wiederaufnahmeversuch bei Netzwerkabbrüchen."
        override val llmManualSelectTitle = "Oder manuell auswählen"
        override val llmPickFileButton = "Lokale Modell-Datei auswählen"
        override val llmPickFileHint = "Bereits heruntergeladene .litertlm-Datei aus dem Gerätespeicher wählen; sie wird einmalig " +
        "in den App-Speicher kopiert."
        override val llmActiveLabel: (String) -> String = { name -> "Aktiv: $name" }
        override val llmProviderSectionTitle = "KI-Anbieter"
        override val llmProviderPresetTitle = "Anbieter-Voreinstellung"
        override val llmOllamaHint = "Ollama (die gleiche OpenAI-kompatible API) benötigt i. d. R. keinen echten API-Key -- " +
        "ein beliebiger Platzhaltertext im Feld oben genügt."
        override val llmOneProviderGatewayHint = "\"OneProvider Gateway\" spricht dasselbe Anthropic-Wire-Format (Tool Use/Tool " +
        "Result-Inhaltsblöcke, Streaming) unter eigenem Host und eigenem API-Key."
        override val baseUrlLabel = "Base URL"
        override val apiKeyLabel = "API-Key"
        override val apiKeyOptionalLabel = "API-Key (optional)"
        override val llmOneProviderExplain: (Int) -> String = { limit ->
        "API-Key optional: leer lassen nutzt automatisch einen in der App hinterlegten OneProvider-Key, " +
            "begrenzt auf $limit Anfragen pro Tag (Reset um Mitternacht UTC). Mit eigenem Key kein Tageslimit, " +
            "wie bei jedem anderen Anbieter."
    }
        override val llmOwnKeyActive = "Eigener Key aktiv -- kein Tageslimit"
        override val llmFreeRequestsRemaining: (Int, Int) -> String = { remaining, limit -> "$remaining von $limit Freianfragen heute übrig (App-Key)" }
        override val llmKeyBeingChecked = "Key wird geprüft …"
        override val llmKeyValid = "Key gültig"
        override val llmKeyOptionalBlankHint = "Kein eigener Key -- \"Speichern\" nutzt das App-Freikontingent"
        override val llmKeyBlankHint = "Kein Key eingetragen -- \"Speichern\" entfernt einen zuvor gespeicherten Key"
        override val llmNotTestedBeforeSave = "Noch nicht getestet – zum Speichern erst testen"
        override val llmModelSectionTitle = "Modell"
        override val llmModelAuto = "Automatisch (empfohlen)"
        override val llmModelCustomChip = "Benutzerdefiniertes Modell eingeben …"
        override val llmModelAutoHint = "\"Automatisch\" nutzt im Chat das schnellere Modell und für die Übersicht/Ampel-Analyse " +
        "sowie Berichte das leistungsstärkste Modell dieses Anbieters."
        override val llmModelIdLabel = "Modell-ID"
        override val llmModelIdInvalidFormat = "Ungültiges Format -- keine Leerzeichen, z. B. \"provider/model-name\" oder \"model-name-v1\"."
        override val llmModelBeingTested = "Modell wird getestet …"
        override val llmModelReachable = "Modell erreichbar"
        override val llmNotTestedBeforeApply = "Noch nicht getestet – zum Übernehmen erst testen"
    },
    dataSourcesScreenStrings = object : DataSourcesScreenStrings {
        override val dsPureChatModeHint = "Pure Chat Mode: Es ist weder ein MCP-Server noch die Nightscout-API konfiguriert. " +
        "GlucoSphere beantwortet Fragen ohne Zugriff auf echte Messdaten."
        override val dsMcpServersTitle: (Int) -> String = { max -> "MCP-Server (max. $max)" }
        override val dsAddMcpServer = "+ MCP-Server hinzufügen"
        override val dsAuthTypeLabel = "Auth-Typ"
        override val authNone = "Keine"
        override val authBearer = "Bearer"
        override val authCustomHeader = "Custom Header"
        override val authOAuth2 = "OAuth2"
        override val dsTransportLabel = "Transport"
        override val dsDataCategoryLabel = "Datenkategorie"
        override val dsToolsCount: (Int) -> String = { n -> "$n Tools" }
        override val dsNameLabel = "Name"
        override val dsNamePlaceholder = "z. B. Nightscout"
        override val dsRestApiHint = "⚡ Direkt-API (i.d.R. schneller)"
        override val dsUrlLabel = "SSE/HTTP-URL"
        override val dsTokenSecretLabel = "Token / Secret"
        override val dsDataSourceEnabled = "Datenquelle aktiviert"
        override val dsQueriedByDashboardChat = "Wird von Dashboard und Chat abgefragt."
        override val dsEnableAfterTestHint = "Kann erst aktiviert werden, nachdem \"Verbindung testen\" erfolgreich war."
        override val dsDiscoveryTitle = "Selbsterkundung (Discovery Modus)"
        override val dsDiscoveryHint = "Ermittelt automatisch, welche Werkzeuge dieser Server bietet, und lässt " +
        "die KI passende Beispielfragen dazu formulieren."
        override val dsExploreButton = "🔍 Erkunden"
        override val dsAutoRunToolsTitle = "Tools automatisch ausführen"
        override val dsAutoRunToolsHint = "Ohne Nachfrage im Chat -- überspringt die \"Soll das Tool ... ausgeführt " +
        "werden?\"-Bestätigung für ALLE Tools dieses Servers."
        override val dsToolsTitle: (Int) -> String = { n -> "Tools ($n)" }
        override val dsToolsAutoApproveHint = "Einzelne Auto-Approve-Häkchen wirken nur, solange der Server-Schalter oben aus ist."
        override val dsToolsLoading = "Tools werden geladen …"
        override val dsTestConnection = "Verbindung testen"
        override val dsTestingConnection = "Verbindung wird getestet …"
        override val dsConnectionSuccessful = "Verbindung erfolgreich"
        override val dsNotTestedBeforeEnable = "Noch nicht getestet – zum Aktivieren erst testen"
        override val dsSavedNotConnected = "Gespeichert, nicht verbunden"
        override val dsConnecting = "Verbinde …"
        override val dsConnected = "Verbunden"
        override val dsConnectionFailed: (String) -> String = { msg -> "Verbindung fehlgeschlagen: $msg" }
        override val dsHideConnectionLog = "Verbindungs-Log ausblenden"
        override val dsShowConnectionLog = "Verbindungs-Log anzeigen"
        override val dsCopyLog = "Log kopieren"
        override val dsOAuth2Hint = "\"Login mit Provider\" erkennt Endpoints und Client-ID normalerweise automatisch " +
        "über die Server-URL oben (OAuth2-Discovery + Dynamic Client Registration) -- " +
        "wie beim Verbinden eines MCP-Servers in Claude. Die Felder unten sind nur für " +
        "Server nötig, die das nicht unterstützen."
        override val dsLoginAgain = "Erneut anmelden (Provider)"
        override val dsLoginWithProvider = "Login mit Provider"
        override val dsOAuth2Discovering = "OAuth2-Konfiguration wird automatisch erkannt …"
        override val dsOAuth2LoggingIn = "Anmeldung läuft (Browser-Tab) …"
        override val dsLoggedIn = "Angemeldet"
        override val dsNotLoggedIn = "Nicht angemeldet"
        override val dsHideManualEndpoints = "Endpoints manuell eintragen ausblenden"
        override val dsShowManualEndpoints = "Endpoints manuell eintragen (falls Discovery nicht funktioniert)"
        override val dsAuthEndpointLabel = "Authorization-Endpoint"
        override val dsTokenEndpointLabel = "Token-Endpoint"
        override val dsClientIdLabel = "Client-ID"
        override val dsScopeOptionalLabel = "Scope (optional)"
        override val dsNightscoutTitle = "Nightscout REST-API (optional)"
        override val dsNightscoutHint = "Direkter Zugriff ohne MCP-Server, z. B. wenn nur die klassische Nightscout-Instanz " +
        "verfügbar ist. Token/API-Secret sind optional (nur für private Instanzen nötig)."
        override val dsNightscoutUrlLabel = "Nightscout-URL"
        override val dsNightscoutDiscoveryHint = "Erkennt automatisch installierte Plugins (Careportal, OpenAPS, IOB, COB, ...)."
        override val dsNightscoutRestApiName = "Nightscout"
        override val dsDiscoverySheetExploring = "Werkzeuge werden erkundet …"
        override val dsDiscoveryNotExploredYet = "Noch nicht erkundet. Tippe unten auf \"Erkunden\", um die verfügbaren Werkzeuge " +
        "und passende Beispielfragen automatisch zu ermitteln."
        override val dsAvailableToolsTitle = "🛠️ Verfügbare Werkzeuge"
        override val dsNoToolsFound = "Keine Werkzeuge gefunden."
        override val dsWhatYouCanAskTitle = "💡 Was du fragen kannst"
        override val dsDataStructureTitle = "📦 Daten-Struktur & Info"
        override val dsReadOnly = "Nur Lesezugriff -- keine Schreibrechte erkannt."
        override val dsWriteCapable = "Schreibzugriff möglich (z. B. Careportal-Einträge/Tool-Aufrufe mit Schreibwirkung)."
        override val dsWriteCapableSuffix = " (Schreibzugriff)"
        override val dsKeyReturnValues: (String) -> String = { values -> "Wichtigste Rückgabewerte: $values" }
        override val dsResources: (String) -> String = { values -> "Ressourcen: $values" }
        override val dsExploreNow = "Jetzt erkunden"
        override val dsExploreAgain = "Erneut erkunden"
    },
    systemPromptScreenStrings = object : SystemPromptScreenStrings {
        override val spPersonalAddressTitle = "Persönliche Ansprache"
        override val spNameLabel = "Dein Name / Vorname"
        override val spNamePlaceholder = "z. B. Andreas"
        override val spNameHint = "Wird im Prompt an der Stelle {userName} eingesetzt, sodass dich das Modell " +
        "immer mit deinem Namen anspricht."
        override val spSaveName = "Namen speichern"
        override val spBasePromptDevHint = "Definiert Rolle, Ton und Verhaltensregeln des Modells. Wird bei jeder Antwort " +
        "mitgeschickt; bei aktiven MCP-Tools werden die Tool-Aufrufanweisungen automatisch ergänzt. " +
        "Nur mit aktiver Entwickler-Lizenz sichtbar/editierbar."
        override val spSystemPromptLabel = "System-Prompt"
        override val spMatchesDefault = "Entspricht dem Standard-Prompt"
        override val spUnsavedChanges = "Ungespeicherte Änderungen"
        override val spResetToDefault = "Auf Standard zurücksetzen"
        override val spAdditionalInstructionsTitle = "Zusätzliche Instruktionen"
        override val spInstructionsTitle = "Anweisungen"
        override val spAdditionalDevHint = "Wird an den Basis-System-Prompt oben angehängt -- nützlich für schnelle, " +
        "temporäre Anpassungen ohne den Basis-Prompt selbst zu verändern."
        override val spAdditionalNonDevHint = "Eigene Anweisungen, die an den (geschützten) Basis-System-Prompt der App " +
        "angehängt werden -- z. B. \"Antworte immer in Stichpunkten\" oder " +
        "\"Erwähne bei jeder Antwort meinen Arzttermin am Freitag\"."
        override val spCollapseInstructions = "Einklappen ▲"
        override val spEditInstructions = "Anweisungen bearbeiten ▼"
        override val spAdditionalInstructionsPlaceholder = "z. B. \"Antworte immer in Stichpunkten.\""
    },
    aboutScreenStrings = object : AboutScreenStrings {
        override val aboutBuildLine: (String, String) -> String = { kind, time -> "$kind-Build vom $time" }
        override val aboutActiveSystemLlm = "Aktives System-LLM"
        override val aboutLicenseTitle = "Lizenz"
        override val aboutDailyLimitReached = "Tageslimit erreicht -- schaltet sich morgen automatisch wieder frei."
        override val aboutMinutesRemaining: (Long) -> String = { minutes -> "Noch $minutes Minute(n) aktive Nutzung heute übrig." }
        override val aboutLicenseKeyLabel = "Lizenzschlüssel"
        override val aboutLicenseKeyPlaceholder = "z. B. GLUCOSPHERE-USER-XXXXXXXX"
        override val aboutSaveLicenseKey = "Lizenzschlüssel speichern"
        override val aboutUsageTitle = "LLM-Verbrauch & Kostenübersicht"
        override val aboutUsageHint = "Nur Cloud-Anbieter (Google/Anthropic/OpenAI-OpenRouter) -- das lokale Modell kostet nichts " +
        "und wird hier nicht mitgezählt."
        override val aboutTodayTokens: (Long) -> String = { n -> "Heute: $n Tokens" }
        override val aboutLast30DaysTokens: (Long) -> String = { n -> "Letzte 30 Tage: $n Tokens" }
        override val aboutEstimatedCost: (String) -> String = { cost -> "Geschätzte Gesamtkosten (30 Tage): $cost €" }
        override val aboutCostDisclaimer = "Grobe Schätzung, gemittelt über günstige Modell-Tarife -- keine echte Abrechnung."
        override val aboutResetUsage = "Verbrauchsstatistik zurücksetzen"
        override val aboutDisclaimerTitle = "Medizinischer Haftungsausschluss"
        override val aboutOpenSourceLicenses = "Open-Source-Lizenzen"
        override val aboutPrivacyNotice = "Datenschutz-Hinweise"
        override val aboutCopyright = "© 2026 GlucoSphere. Alle Rechte vorbehalten."
        override val aboutFooterDisclaimer = "Diese App ist kein Medizinprodukt und ersetzt keine ärztliche Beratung."
        override val aboutShowDisclaimer = "Haftungsausschluss anzeigen"
        override val aboutPrivacyDialogTitle = "Datenschutz"
        override val aboutDisclaimerText = 
        "GlucoSphere ist kein Medizinprodukt und ersetzt keine ärztliche Diagnose, Beratung oder Behandlung. " +
            "Alle angezeigten Werte, Analysen und KI-generierten Hinweise dienen ausschließlich der " +
            "persönlichen Information und Unterstützung im Alltag. Verlasse dich bei Therapieentscheidungen " +
            "(z. B. Insulindosierung) niemals allein auf diese App -- sprich Auffälligkeiten und Warnwerte " +
            "immer mit deinem Diabetologen bzw. Diabetesberater ab."
        override val aboutLicensesText = """Diese App verwendet u. a. folgende Open-Source-Komponenten:

• Jetpack Compose & Material3 (Apache-2.0) -- Google
• Kotlin Coroutines & kotlinx.serialization (Apache-2.0) -- JetBrains
• Ktor Client (Apache-2.0) -- JetBrains
• AndroidX DataStore, Browser, Lifecycle (Apache-2.0) -- Google
• Google LiteRT-LM (Apache-2.0) -- Google

Vollständige Lizenztexte sind im jeweiligen Projekt-Repository der Bibliothek einsehbar."""
        override val aboutPrivacyText = """Datenschutz-Hinweise

GlucoSphere speichert deine Einstellungen (Datenquellen, API-Schlüssel, Chatverlauf) ausschließlich lokal auf diesem Gerät -- es gibt keinen eigenen GlucoSphere-Server.

Bei Nutzung eines Cloud-KI-Anbieters (Google, Anthropic, OpenAI/OpenRouter) werden deine Anfragen inkl. ggf. abgerufener Gesundheitsdaten direkt an den jeweiligen Anbieter zur Verarbeitung übermittelt -- es gelten dessen eigene Datenschutzbestimmungen. Bei "Lokales Modell" verlassen keine Daten das Gerät.

Verbindungen zu konfigurierten MCP-Servern (z. B. Nightscout, Glooko, Withings) sowie zur direkten Nightscout REST-API erfolgen ausschließlich mit den von dir hinterlegten Zugangsdaten."""
    },
    backupScreenStrings = object : BackupScreenStrings {
        override val backupExportTitle = "Einstellungen exportieren"
        override val backupExportHint = "Exportiert API-Schlüssel, das gewählte LLM, den System-Prompt, MCP-Server-Adressen " +
        "und die Verbrauchsstatistik als Datei -- zum lokalen Ablegen oder Hochladen in einen " +
        "Cloud-Speicher deiner Wahl. Der Chatverlauf ist NICHT enthalten."
        override val backupEncryptCheckbox = "Mit Passwort verschlüsseln?"
        override val backupPasswordLabel = "Passwort"
        override val backupImportTitle = "Einstellungen importieren"
        override val backupImportHint = "Überschreibt die oben genannten Einstellungen mit dem Inhalt der ausgewählten " +
        "Backup-Datei. Ist die Datei verschlüsselt, wirst du anschließend nach dem Passwort gefragt."
        override val backupImporting = "Importiere …"
        override val backupImportSuccess = "Import erfolgreich -- die Einstellungen wurden aktualisiert."
        override val backupWrongPassword = "Falsches Passwort."
        override val backupPasswordRequiredTitle = "Passwort erforderlich"
        override val backupWrongPasswordRetry = "Falsches Passwort -- bitte erneut versuchen."
        override val backupEncryptedFileHint = "Diese Backup-Datei ist verschlüsselt. Bitte gib das Passwort ein, mit dem sie exportiert wurde."
        override val backupDecrypt = "Entschlüsseln"
    },
    performanceLogScreenStrings = object : PerformanceLogScreenStrings {
        override val perfHint: (Int) -> String = { max ->
        "Protokolliert jede Cloud-LLM-Anfrage (Chat und Übersicht) mit Anbieter, Modell, " +
            "Token-Verbrauch, Anzahl Tool-Aufrufe, Dauer und -- bei einer fehlgeschlagenen Anfrage -- " +
            "der genauen Fehlermeldung des Anbieters, die die Übersicht/der Chat sonst nur gekürzt " +
            "anzeigt. Die letzten $max Einträge, neueste zuerst. \"Teilen\" " +
            "exportiert genau das als Text, z. B. für einen Fehlerbericht. Das lokale Modell wird " +
            "nicht erfasst (kostet keine Tokens, verlässt nie das Gerät)."
    }
        override val perfShareChooserTitle = "Performance-Log teilen"
        override val perfClearLog = "Log leeren"
        override val perfNoEntries = "Noch keine Einträge -- wird ab der nächsten Chat- oder Übersicht-Anfrage an einen " +
        "Cloud-Anbieter befüllt."
        override val perfDebugLogTitle = "Debug-Log (Entwickler)"
        override val perfDebugLogHint: (Int) -> String = { max ->
        "Chronologische Ablaufverfolgung pro Runde/Tool-Aufruf/Fehler im Klartext -- " +
            "die letzten $max Einträge. Deutlich ausführlicher als das " +
            "Performance-Log oben, deshalb standardmäßig aus, und kann je nach " +
            "Tool-Ergebnissen Ausschnitte deiner Gesundheitsdaten enthalten -- vor dem " +
            "Teilen kurz durchsehen."
    }
        override val perfShareDebugLogChooserTitle = "Debug-Log teilen"
        override val perfNoDebugEntries = "Noch keine Einträge."
        override val perfTokensToolCalls: (Long, Long, Int) -> String = { prompt, completion, toolCalls -> "$prompt+$completion Tokens · $toolCalls Tool-Aufrufe" }
        override val perfNoEntriesShareText = "GlucoSphere – Performance-Log: keine Einträge."
        override val perfShareTextHeader: (Int) -> String = { n -> "GlucoSphere – Performance-Log ($n Einträge)" }
        override val perfNoDebugEntriesShareText = "GlucoSphere – Debug-Log: keine Einträge."
        override val perfDebugShareTextHeader: (Int) -> String = { n -> "GlucoSphere – Debug-Log ($n Einträge)" }
    },
    helpScreenStrings = object : HelpScreenStrings {
        override val helpGeminiKeyTitle = "Kostenloser Gemini-API-Key (Google AI Studio)"
        override val helpGeminiKeySteps = "1. Auf aistudio.google.com mit einem beliebigen Google-Konto anmelden.\n" +
        "2. Oben links auf \"Get API key\" klicken und einen neuen Key erstellen -- " +
        "keine Kreditkarte nötig, dauert unter 5 Minuten.\n" +
        "3. Den Key kopieren und in GlucoSphere unter Einstellungen -> LLM-Konfiguration -> " +
        "\"Google Gemini API\" einfügen, dann \"Testen\" -> \"Speichern\".\n" +
        "4. Der kostenlose Tarif reicht für den normalen Chat-/Übersicht-Gebrauch " +
        "völlig aus; die genauen Limits (Anfragen pro Minute/Tag) stehen auf der " +
        "gleichen Seite unter \"Rate limits\"."
        override val helpOpenGoogleAiStudio = "Google AI Studio öffnen"
        override val helpMcpSuggestionsTitle = "MCP-Server für Datenquellen -- Vorschläge, keine offizielle Empfehlung"
        override val helpMcpSuggestionsHint = "Die folgenden Links sind Vorschläge aus einer Recherche (Stand: 2026-07-26), keine von " +
        "GlucoSphere geprüften oder betriebenen Server -- MCP ist ein offener Standard, jeder " +
        "MCP-Server, der zum jeweiligen Thema passende Tools bereitstellt, lässt sich unter " +
        "Einstellungen -> Datenquellen genauso eintragen, unabhängig davon, ob er hier gelistet " +
        "ist. Community-Projekte wechseln Pflegestatus/Autor häufig -- prüfe vor dem Verbinden " +
        "immer selbst README und letzten Commit.\n\n" +
        "Ein MCP-Server läuft üblicherweise auf einem eigenen Rechner/Server (nicht auf dem " +
        "Smartphone) und muss von deinem Handy aus per HTTPS erreichbar sein -- entweder direkt " +
        "im gleichen WLAN, über ein VPN (z. B. Tailscale/WireGuard) zu deinem Heimnetz, oder " +
        "über einen Reverse Proxy (z. B. nginx/Caddy) mit eigenem TLS-Zertifikat, falls du ihn " +
        "bewusst aus dem offenen Internet erreichbar machen willst. Bearer-Token/API-Keys sind " +
        "Zugangsdaten wie ein Passwort -- niemals teilen, bei Verdacht auf Kompromittierung im " +
        "MCP-Server neu erzeugen und in GlucoSphere aktualisieren."
        override val helpNightscoutDesc = "Blutzuckerwerte, Behandlungen, Profile und Statistiken direkt aus deiner Nightscout-Instanz."
        override val helpGlookoDesc = "Kein öffentliches, aktiv gepflegtes Glooko-MCP-Repository gefunden (Stand: Recherche " +
        "vom 2026-07-26). Falls du eines kennst oder selbst betreibst, kannst du es trotzdem ganz " +
        "normal unter Einstellungen -> Datenquellen als eigenen MCP-Server eintragen."
        override val helpWithingsDesc = "Gewicht, Körperzusammensetzung, Schlaf, Aktivität via OAuth2 gegen die offizielle " +
        "Withings-API. Mehrere unabhängige Community-Implementierungen -- README vor der Nutzung prüfen."
        override val helpWithingsAlt1 = "Alternative: Schimmilab/withings-mcp-server"
        override val helpWithingsAlt2 = "Alternative: davidmosiah/withings-mcp (lokal, Token verlassen das Gerät nicht)"
        override val helpWithingsAlt3 = "Withings Developer Portal (eigene OAuth2-App registrieren)"
        override val helpFeelfitDesc = "Körperzusammensetzungsdaten von FeelFit-Waagen (Gewicht, Körperfett, Muskelmasse u. a.). " +
        "Kein direktes GitHub-Repo gefunden, nur folgende Marktplatz-Einträge mit Setup-Anleitung:"
        override val helpGoogleHealthDesc = "Umfassender Gesundheitsdaten-Zugriff inkl. der Migration von Fitbit auf die neue " +
        "Google-Health-API -- unterstützt u. a. fotobasiertes Ernährungs-Logging."
        override val helpGoogleHealthAlt = "Alternative: davidmosiah/google-health-mcp (lokal-first, Fitbit + Pixel Watch)"
        override val helpStravaTitle = "Strava MCP (Sport / Bewegung)"
        override val helpStravaDesc = "Für reine Sport-/Trainingsdaten (Aktivitäten, Strecken, Segmente, Trainingsverlauf) " +
        "als Alternative/Ergänzung zu Google Health, falls Sport primär über Strava statt Fitbit/Health " +
        "Connect getrackt wird. Mehrere unabhängige Implementierungen -- README vor der Nutzung prüfen."
        override val helpStravaSettingsLink = "Strava API-Einstellungen (eigene App registrieren)"
        override val helpOpenGithubRepo = "GitHub-Repository öffnen"
    },
    profileScreenStrings = object : ProfileScreenStrings {
        override val profileTitle = "Profil / Benutzer"
        override val profileUserTypeTitle = "Benutzertyp"
        override val profileHint = "Bestimmt Tonalität und fachlichen Fokus der KI-Antworten im Chat und in der " +
        "Übersicht -- die gleichen Daten werden je nach Rolle unterschiedlich erklärt."
        override val roleDiabetikerLabel = "Diabetiker"
        override val roleDiabetikerDesc = "Persönlich, empathisch, praxisorientiert -- Alltagstipps, Blutzuckermanagement, KE/BE-Schätzungen."
        override val roleFachpersonalLabel = "Medizinisches Fachpersonal (Diabetes-Team)"
        override val roleFachpersonalDesc = "Fachlich-neutral, präzise -- TIR, %CV, AGP-Profile, Insulindosierung, Leitlinien-Konformität."
        override val roleAngehorigeLabel = "Angehörige (Diabetes-Laien)"
        override val roleAngehorigeDesc = "Einfühlsam, beruhigend, barrierefrei -- Notfall-Signale erkennen, klare Handlungsempfehlungen."
    },
    viewModelChatTailStrings = object : ViewModelChatTailStrings {
        override val toolRunning = "wird ausgeführt …"
        override val toolDeclined = "abgelehnt"
        override val toolCompleted = "abgeschlossen"
        override val autoRoutedTo: (String) -> String = { model -> "Auto-routed zu: $model" }
        override val pdfAnswerTitle = "GlucoSphere – Antwort"
    },
)

private val StringsEn = Strings(
    mainTabsScreenStrings = object : MainTabsScreenStrings {
        override val tabOverview = "Overview"
        override val tabChat = "Chat"
        override val deleteHistoryTitle = "Delete chat history"
        override val deleteHistoryText = "Do you really want to delete the entire chat history?"
        override val deleteHistoryConfirm = "Delete"
        override val deleteHistoryDismiss = "Cancel"
        override val deleteHistoryContentDescription = "Delete chat history"
        override val settingsContentDescription = "Settings"
    },
    dashboardSectionStrings = object : DashboardSectionStrings {
        override val overviewTitle = "Overview"
        override val refresh = "Refresh"
        override val editFilters = "Edit filters ▼"
        override val collapseFilters = "Collapse filters ▲"
        override val dataSources = "Data sources"
        override val singleSelectHint = "Period > 5 days: only one source per category selectable."
        override val noDataSuffix = " (no data)"
        override val evaluatedPeriod = "Evaluated period"
        override val comparisonPeriod = "Compared to previous period"
        override val currentValueLabel = "Current value"
        override val computingComparisonPeriod = "📊 Computing comparison period …"
        override val noDashboardDataTitle = "No data yet"
        override val noDashboardDataHint = "The overview no longer refreshes automatically. Pull down to refresh, " +
        "or tap \"Refresh\"."
        override val timeInRange = "Time in Range"
        override val metricsTitle = "Metrics"
        override val metricTir = "Time in Range (70-180)"
        override val metricHypo = "Hypoglycemia (<70)"
        override val metricSevereHypo = "of which severe (<54)"
        override val metricHyper = "Hyperglycemia (>180)"
        override val metricVariability = "Variability (%CV)"
        override val metricAvgGlucose = "Avg. glucose"
        override val metricHbA1c = "Estimated HbA1c (GMI)"
        override val bodyCompositionTitle = "Body composition"
        override val bodyWeight = "Weight"
        override val bodyFat = "Body fat percentage"
        override val bodyTrend3Month = "3-month trend"
        override val trendUp = "Rising ↗"
        override val trendDown = "Falling ↘"
        override val trendStable = "Stable ➔"
        override val trendUnknown = "Not enough readings"
        override val trendVsPrevious = "Trend vs. previous period"
        override val summaryTitle = "Summary"
        override val tipsTitle = "AI tips"
        override val applyRange = "Apply"
        override val allSources = "all sources"
        override val dateFrom: (String) -> String = { date -> "From: $date" }
        override val dateTo: (String) -> String = { date -> "To: $date" }
        override val lastUpdated: (String) -> String = { time -> "Last updated: $time" }
    },
    chatSectionStrings = object : ChatSectionStrings {
        override val includeMcpServers = "Include MCP servers"
        override val chatPlaceholder = "Ask a question, e.g. \"How was my workout yesterday?\""
        override val send = "Send"
        override val cancel = "Cancel"
        override val hideReasoning = "💭 Hide reasoning"
        override val showReasoning: (tokenEstimate: Int) -> String = { tokens -> "💭 Show reasoning (~$tokens tokens)" }
        override val listening = "Listening …"
        override val micPrompt = "Speak …"
        override val messageCopied = "Message copied"
        override val aiThinking = "💭 AI is thinking …"
        override val aiCallingTool: (serverName: String) -> String = { serverName -> "🔧 Querying $serverName …" }
        override val aiGenerating = "✍️ Composing answer …"
        override val copyAction = "Copy"
        override val shareAction = "Share"
        override val sharePdfAction = "Share as PDF"
        override val shareContentDescription = "Share overview"
        override val voiceModeListening = "🎙️ Listening …"
        override val voiceModeSpeaking = "🔊 Speaking … (tap to stop)"
        override val voiceModeIdle = "Voice mode active"
        override val voiceModeExit = "Exit"
        override val activeToolsLabel = "Active data sources"
        override val youLabel = "You"
        override val mcpDisabledLabel = "none (MCP disabled)"
        override val shareChatContentDescription = "Share chat history"
    },
    settingsOverviewScreenStrings = object : SettingsOverviewScreenStrings {
        override val settingsTitle = "Settings"
        override val languageLabel = "Language"
        override val languageHint = "Controls voice input/output (microphone/read-aloud) as well as the traffic-light " +
        "label (Grün/Gelb/Rot resp. Green/Yellow/Red)."
        override val appearanceSectionTitle = "Appearance"
        override val llmConfigMenuTitle = "LLM configuration"
        override val dataSourcesMenuTitle = "Data sources (MCP & API)"
        override val systemPromptMenuTitle = "System prompt"
        override val aboutMenuTitle = "About GlucoSphere"
        override val aboutMenuSubtitle = "Version, licenses, privacy"
        override val backupMenuTitle = "Backup & configuration"
        override val backupMenuSubtitle = "Export/import settings"
        override val performanceLogMenuTitle = "Performance log"
        override val performanceLogMenuSubtitle = "Requests: provider, model, tokens, duration"
        override val helpMenuTitle = "Help & MCP guides"
        override val helpMenuSubtitle = "API keys, MCP server links, security"
    },
    settingsOverviewSubtitlesStrings = object : SettingsOverviewSubtitlesStrings {
        override val settingsDownloadRunning = "Download running …"
        override val settingsLocalModelActive: (String) -> String = { name -> "Local model active: $name" }
        override val settingsLoadError = "Error loading"
        override val settingsLoadingModel = "Loading …"
        override val settingsNoModelConfigured = "No model configured"
        override val settingsApiActive: (String) -> String = { name -> "$name active" }
        override val settingsOneProviderFreeActive: (Int, Int) -> String = { remaining, limit -> "OneProvider free quota active ($remaining/$limit left today)" }
        override val settingsPureChatModeSubtitle = "Pure Chat Mode (no data source)"
        override val settingsMcpConnected: (Int, Int) -> String = { connected, total -> "$connected of $total MCP server(s) connected" }
        override val settingsConfiguredNotConnected = "Configured, not connected"
        override val settingsPromptStandard = "Default"
        override val settingsPromptCustomized = "Customized"
    },
    genericStrings = object : GenericStrings {
        override val genericBack = "Back"
        override val genericSave = "Save"
        override val genericTest = "Test"
        override val genericRemove = "Remove"
        override val genericYes = "Yes"
        override val genericNo = "No"
        override val sourceChoiceQuestion: (List<String>) -> String = { names ->
            "Several sources are available for this: ${names.joinToString(", ")}. Which one should I use?"
        }
        override val sourceChoiceAllOption = "All sources"
        override val genericClose = "Close"
        override val genericApply = "Apply"
        override val genericEnabled = "Enabled"
        override val genericDisabled = "Disabled"
        override val genericErrorPrefix: (String) -> String = { msg -> "Error: $msg" }
    },
    llmConfigScreenStrings = object : LlmConfigScreenStrings {
        override val llmProviderLocal = "Local model (LiteRT / Gemma)"
        override val llmProviderGemini = "Google Gemini API"
        override val llmProviderClaude = "Anthropic Claude API"
        override val llmProviderOpenAi = "OpenAI API / OpenRouter"
        override val llmProviderDeepSeek = "DeepSeek API"
        override val llmProviderOneProvider = "OneProvider (own key or free quota)"
        override val llmGemmaTitle = "Gemma (LiteRT-LM, .litertlm)"
        override val llmGemmaSubtitle = "Runs via Google's LiteRT-LM SDK on the device's GPU."
        override val llmNoModelSelected = "No model selected"
        override val llmSizeOnDevice: (String) -> String = { gb -> "$gb GB on device" }
        override val llmImportingFile = "Importing file …"
        override val llmEngineIdle = "Status: no model loaded"
        override val llmEngineLoadingStatus = "Status: loading …"
        override val llmEngineReady = "Model ready"
        override val llmEngineGenerating = "Model ready (currently in use)"
        override val llmEngineErrorStatus: (String) -> String = { msg -> "Status: Error – $msg" }
        override val llmDownloadDirectTitle = "Download directly"
        override val llmDownloadUrlLabel = "Download URL (.litertlm)"
        override val llmDownloadButton = "Download model (~2.4 GB)"
        override val llmDownloadHint = "Runs via the system download manager: progress shown in the notification shade, " +
        "automatic retry on network interruptions."
        override val llmManualSelectTitle = "Or select manually"
        override val llmPickFileButton = "Select local model file"
        override val llmPickFileHint = "Select an already-downloaded .litertlm file from device storage; it's copied " +
        "into app storage once."
        override val llmActiveLabel: (String) -> String = { name -> "Active: $name" }
        override val llmProviderSectionTitle = "AI provider"
        override val llmProviderPresetTitle = "Provider preset"
        override val llmOllamaHint = "Ollama (the same OpenAI-compatible API) usually needs no real API key -- " +
        "any placeholder text in the field above is enough."
        override val llmOneProviderGatewayHint = "\"OneProvider Gateway\" speaks the same Anthropic wire format (tool use/tool " +
        "result content blocks, streaming) under its own host and API key."
        override val baseUrlLabel = "Base URL"
        override val apiKeyLabel = "API key"
        override val apiKeyOptionalLabel = "API key (optional)"
        override val llmOneProviderExplain: (Int) -> String = { limit ->
        "API key optional: leave blank to automatically use an app-embedded OneProvider key, " +
            "limited to $limit requests per day (resets at midnight UTC). With your own key there's " +
            "no daily limit, same as any other provider."
    }
        override val llmOwnKeyActive = "Own key active -- no daily limit"
        override val llmFreeRequestsRemaining: (Int, Int) -> String = { remaining, limit -> "$remaining of $limit free requests left today (app key)" }
        override val llmKeyBeingChecked = "Checking key …"
        override val llmKeyValid = "Key valid"
        override val llmKeyOptionalBlankHint = "No own key -- \"Save\" uses the app's free quota"
        override val llmKeyBlankHint = "No key entered -- \"Save\" removes a previously saved key"
        override val llmNotTestedBeforeSave = "Not tested yet – test before saving"
        override val llmModelSectionTitle = "Model"
        override val llmModelAuto = "Automatic (recommended)"
        override val llmModelCustomChip = "Enter custom model …"
        override val llmModelAutoHint = "\"Automatic\" uses the faster model in Chat, and this provider's most capable " +
        "model for the Overview/traffic-light analysis and reports."
        override val llmModelIdLabel = "Model ID"
        override val llmModelIdInvalidFormat = "Invalid format -- no spaces, e.g. \"provider/model-name\" or \"model-name-v1\"."
        override val llmModelBeingTested = "Testing model …"
        override val llmModelReachable = "Model reachable"
        override val llmNotTestedBeforeApply = "Not tested yet – test before applying"
    },
    dataSourcesScreenStrings = object : DataSourcesScreenStrings {
        override val dsPureChatModeHint = "Pure Chat Mode: neither an MCP server nor the Nightscout API is configured. " +
        "GlucoSphere answers questions without access to real measurement data."
        override val dsMcpServersTitle: (Int) -> String = { max -> "MCP servers (max. $max)" }
        override val dsAddMcpServer = "+ Add MCP server"
        override val dsAuthTypeLabel = "Auth type"
        override val authNone = "None"
        override val authBearer = "Bearer"
        override val authCustomHeader = "Custom Header"
        override val authOAuth2 = "OAuth2"
        override val dsTransportLabel = "Transport"
        override val dsDataCategoryLabel = "Data category"
        override val dsToolsCount: (Int) -> String = { n -> "$n tools" }
        override val dsNameLabel = "Name"
        override val dsNamePlaceholder = "e.g. Nightscout"
        override val dsRestApiHint = "⚡ Direct API (usually faster)"
        override val dsUrlLabel = "SSE/HTTP URL"
        override val dsTokenSecretLabel = "Token / Secret"
        override val dsDataSourceEnabled = "Data source enabled"
        override val dsQueriedByDashboardChat = "Queried by the Overview and Chat."
        override val dsEnableAfterTestHint = "Can only be enabled after \"Test connection\" succeeds."
        override val dsDiscoveryTitle = "Self-discovery (Discovery Mode)"
        override val dsDiscoveryHint = "Automatically determines which tools this server offers, and has the AI " +
        "draft matching example questions."
        override val dsExploreButton = "🔍 Explore"
        override val dsAutoRunToolsTitle = "Auto-run tools"
        override val dsAutoRunToolsHint = "No confirmation in chat -- skips the \"Should the tool ... be run?\" " +
        "prompt for ALL of this server's tools."
        override val dsToolsTitle: (Int) -> String = { n -> "Tools ($n)" }
        override val dsToolsAutoApproveHint = "Individual auto-approve checkboxes only take effect while the server switch above is off."
        override val dsToolsLoading = "Loading tools …"
        override val dsTestConnection = "Test connection"
        override val dsTestingConnection = "Testing connection …"
        override val dsConnectionSuccessful = "Connection successful"
        override val dsNotTestedBeforeEnable = "Not tested yet – test before enabling"
        override val dsSavedNotConnected = "Saved, not connected"
        override val dsConnecting = "Connecting …"
        override val dsConnected = "Connected"
        override val dsConnectionFailed: (String) -> String = { msg -> "Connection failed: $msg" }
        override val dsHideConnectionLog = "Hide connection log"
        override val dsShowConnectionLog = "Show connection log"
        override val dsCopyLog = "Copy log"
        override val dsOAuth2Hint = "\"Log in with provider\" usually detects endpoints and client ID automatically " +
        "from the server URL above (OAuth2 discovery + dynamic client registration) -- " +
        "the same as connecting an MCP server in Claude. The fields below are only needed for " +
        "servers that don't support this."
        override val dsLoginAgain = "Log in again (provider)"
        override val dsLoginWithProvider = "Log in with provider"
        override val dsOAuth2Discovering = "Auto-detecting OAuth2 configuration …"
        override val dsOAuth2LoggingIn = "Logging in (browser tab) …"
        override val dsLoggedIn = "Logged in"
        override val dsNotLoggedIn = "Not logged in"
        override val dsHideManualEndpoints = "Hide manual endpoint entry"
        override val dsShowManualEndpoints = "Enter endpoints manually (if discovery doesn't work)"
        override val dsAuthEndpointLabel = "Authorization endpoint"
        override val dsTokenEndpointLabel = "Token endpoint"
        override val dsClientIdLabel = "Client ID"
        override val dsScopeOptionalLabel = "Scope (optional)"
        override val dsNightscoutTitle = "Nightscout REST API (optional)"
        override val dsNightscoutHint = "Direct access without an MCP server, e.g. when only the classic Nightscout instance " +
        "is available. Token/API secret are optional (only needed for private instances)."
        override val dsNightscoutUrlLabel = "Nightscout URL"
        override val dsNightscoutDiscoveryHint = "Automatically detects installed plugins (Careportal, OpenAPS, IOB, COB, ...)."
        override val dsNightscoutRestApiName = "Nightscout"
        override val dsDiscoverySheetExploring = "Exploring tools …"
        override val dsDiscoveryNotExploredYet = "Not explored yet. Tap \"Explore\" below to automatically determine the " +
        "available tools and matching example questions."
        override val dsAvailableToolsTitle = "🛠️ Available tools"
        override val dsNoToolsFound = "No tools found."
        override val dsWhatYouCanAskTitle = "💡 What you can ask"
        override val dsDataStructureTitle = "📦 Data structure & info"
        override val dsReadOnly = "Read-only access -- no write permissions detected."
        override val dsWriteCapable = "Write access possible (e.g. Careportal entries/tool calls with write effect)."
        override val dsWriteCapableSuffix = " (write access)"
        override val dsKeyReturnValues: (String) -> String = { values -> "Key return values: $values" }
        override val dsResources: (String) -> String = { values -> "Resources: $values" }
        override val dsExploreNow = "Explore now"
        override val dsExploreAgain = "Explore again"
    },
    systemPromptScreenStrings = object : SystemPromptScreenStrings {
        override val spPersonalAddressTitle = "Personal address"
        override val spNameLabel = "Your name / first name"
        override val spNamePlaceholder = "e.g. Andreas"
        override val spNameHint = "Inserted into the prompt at {userName}, so the model always addresses you by name."
        override val spSaveName = "Save name"
        override val spBasePromptDevHint = "Defines the model's role, tone, and behavior rules. Sent along with every " +
        "answer; tool-call instructions are appended automatically when MCP tools are active. " +
        "Only visible/editable with an active Developer license."
        override val spSystemPromptLabel = "System prompt"
        override val spMatchesDefault = "Matches the default prompt"
        override val spUnsavedChanges = "Unsaved changes"
        override val spResetToDefault = "Reset to default"
        override val spAdditionalInstructionsTitle = "Additional instructions"
        override val spInstructionsTitle = "Instructions"
        override val spAdditionalDevHint = "Appended to the base system prompt above -- useful for quick, temporary " +
        "adjustments without changing the base prompt itself."
        override val spAdditionalNonDevHint = "Your own instructions, appended to the app's (protected) base system prompt -- " +
        "e.g. \"Always answer in bullet points\" or \"Mention my Friday doctor's appointment in every answer\"."
        override val spCollapseInstructions = "Collapse ▲"
        override val spEditInstructions = "Edit instructions ▼"
        override val spAdditionalInstructionsPlaceholder = "e.g. \"Always answer in bullet points.\""
    },
    aboutScreenStrings = object : AboutScreenStrings {
        override val aboutBuildLine: (String, String) -> String = { kind, time -> "$kind build from $time" }
        override val aboutActiveSystemLlm = "Active system LLM"
        override val aboutLicenseTitle = "License"
        override val aboutDailyLimitReached = "Daily limit reached -- unlocks automatically again tomorrow."
        override val aboutMinutesRemaining: (Long) -> String = { minutes -> "$minutes minute(s) of active use left today." }
        override val aboutLicenseKeyLabel = "License key"
        override val aboutLicenseKeyPlaceholder = "e.g. GLUCOSPHERE-USER-XXXXXXXX"
        override val aboutSaveLicenseKey = "Save license key"
        override val aboutUsageTitle = "LLM usage & cost overview"
        override val aboutUsageHint = "Cloud providers only (Google/Anthropic/OpenAI-OpenRouter) -- the local model costs " +
        "nothing and isn't counted here."
        override val aboutTodayTokens: (Long) -> String = { n -> "Today: $n tokens" }
        override val aboutLast30DaysTokens: (Long) -> String = { n -> "Last 30 days: $n tokens" }
        override val aboutEstimatedCost: (String) -> String = { cost -> "Estimated total cost (30 days): €$cost" }
        override val aboutCostDisclaimer = "Rough estimate, averaged over low-cost model tiers -- not a real bill."
        override val aboutResetUsage = "Reset usage statistics"
        override val aboutDisclaimerTitle = "Medical disclaimer"
        override val aboutOpenSourceLicenses = "Open-source licenses"
        override val aboutPrivacyNotice = "Privacy notice"
        override val aboutCopyright = "© 2026 GlucoSphere. All rights reserved."
        override val aboutFooterDisclaimer = "This app is not a medical device and does not replace medical advice."
        override val aboutShowDisclaimer = "Show disclaimer"
        override val aboutPrivacyDialogTitle = "Privacy"
        override val aboutDisclaimerText = 
        "GlucoSphere is not a medical device and does not replace a physician's diagnosis, advice, or " +
            "treatment. All values, analyses, and AI-generated notes shown are for personal information " +
            "and everyday support only. Never rely solely on this app for treatment decisions (e.g. insulin " +
            "dosing) -- always discuss abnormal or warning values with your diabetologist or diabetes educator."
        override val aboutLicensesText = """This app uses, among others, the following open-source components:

• Jetpack Compose & Material3 (Apache-2.0) -- Google
• Kotlin Coroutines & kotlinx.serialization (Apache-2.0) -- JetBrains
• Ktor Client (Apache-2.0) -- JetBrains
• AndroidX DataStore, Browser, Lifecycle (Apache-2.0) -- Google
• Google LiteRT-LM (Apache-2.0) -- Google

Full license texts are available in each library's own project repository."""
        override val aboutPrivacyText = """Privacy notice

GlucoSphere stores your settings (data sources, API keys, chat history) exclusively locally on this device -- there is no GlucoSphere server of its own.

When using a cloud AI provider (Google, Anthropic, OpenAI/OpenRouter), your requests -- including any retrieved health data -- are sent directly to that provider for processing; its own privacy policy applies. With "Local model", no data ever leaves the device.

Connections to configured MCP servers (e.g. Nightscout, Glooko, Withings) and to the direct Nightscout REST API are made exclusively with the credentials you have entered."""
    },
    backupScreenStrings = object : BackupScreenStrings {
        override val backupExportTitle = "Export settings"
        override val backupExportHint = "Exports API keys, the selected LLM, the system prompt, MCP server addresses, " +
        "and usage statistics as a file -- to save locally or upload to a cloud storage of your choice. " +
        "Chat history is NOT included."
        override val backupEncryptCheckbox = "Encrypt with password?"
        override val backupPasswordLabel = "Password"
        override val backupImportTitle = "Import settings"
        override val backupImportHint = "Overwrites the settings listed above with the content of the selected backup file. " +
        "If the file is encrypted, you'll be asked for the password afterward."
        override val backupImporting = "Importing …"
        override val backupImportSuccess = "Import successful -- settings have been updated."
        override val backupWrongPassword = "Wrong password."
        override val backupPasswordRequiredTitle = "Password required"
        override val backupWrongPasswordRetry = "Wrong password -- please try again."
        override val backupEncryptedFileHint = "This backup file is encrypted. Please enter the password it was exported with."
        override val backupDecrypt = "Decrypt"
    },
    performanceLogScreenStrings = object : PerformanceLogScreenStrings {
        override val perfHint: (Int) -> String = { max ->
        "Logs every cloud LLM request (Chat and Overview) with provider, model, token usage, number " +
            "of tool calls, duration, and -- for a failed request -- the provider's exact error message, " +
            "which the Overview/Chat otherwise only shows truncated. The last $max entries, newest first. " +
            "\"Share\" exports exactly that as text, e.g. for a bug report. The local model isn't recorded " +
            "(costs no tokens, never leaves the device)."
    }
        override val perfShareChooserTitle = "Share performance log"
        override val perfClearLog = "Clear log"
        override val perfNoEntries = "No entries yet -- populated from the next Chat or Overview request to a cloud provider."
        override val perfDebugLogTitle = "Debug log (developer)"
        override val perfDebugLogHint: (Int) -> String = { max ->
        "Chronological trace per round/tool call/error, in plain text -- the last $max entries. " +
            "Considerably more verbose than the performance log above, so it's off by default, and " +
            "depending on tool results may include snippets of your health data -- review briefly " +
            "before sharing."
    }
        override val perfShareDebugLogChooserTitle = "Share debug log"
        override val perfNoDebugEntries = "No entries yet."
        override val perfTokensToolCalls: (Long, Long, Int) -> String = { prompt, completion, toolCalls -> "$prompt+$completion tokens · $toolCalls tool calls" }
        override val perfNoEntriesShareText = "GlucoSphere – Performance log: no entries."
        override val perfShareTextHeader: (Int) -> String = { n -> "GlucoSphere – Performance log ($n entries)" }
        override val perfNoDebugEntriesShareText = "GlucoSphere – Debug log: no entries."
        override val perfDebugShareTextHeader: (Int) -> String = { n -> "GlucoSphere – Debug log ($n entries)" }
    },
    helpScreenStrings = object : HelpScreenStrings {
        override val helpGeminiKeyTitle = "Free Gemini API key (Google AI Studio)"
        override val helpGeminiKeySteps = "1. Sign in at aistudio.google.com with any Google account.\n" +
        "2. Click \"Get API key\" in the top left and create a new key -- no credit card needed, " +
        "takes under 5 minutes.\n" +
        "3. Copy the key and paste it into GlucoSphere under Settings -> LLM configuration -> " +
        "\"Google Gemini API\", then \"Test\" -> \"Save\".\n" +
        "4. The free tier is fully sufficient for normal Chat/Overview use; the exact limits " +
        "(requests per minute/day) are on the same page under \"Rate limits\"."
        override val helpOpenGoogleAiStudio = "Open Google AI Studio"
        override val helpMcpSuggestionsTitle = "MCP servers for data sources -- suggestions, not an official recommendation"
        override val helpMcpSuggestionsHint = "The links below are suggestions from research (as of 2026-07-26), not servers " +
        "checked or operated by GlucoSphere -- MCP is an open standard, and any MCP server that provides " +
        "tools matching the relevant topic can be entered under Settings -> Data sources the same way, " +
        "whether or not it's listed here. Community projects frequently change maintenance status/author -- " +
        "always check a repo's own README and last commit before connecting.\n\n" +
        "An MCP server usually runs on its own computer/server (not on the phone) and must be reachable " +
        "from your phone over HTTPS -- either directly on the same Wi-Fi, via a VPN (e.g. Tailscale/" +
        "WireGuard) to your home network, or via a reverse proxy (e.g. nginx/Caddy) with its own TLS " +
        "certificate if you want it deliberately reachable from the open internet. Bearer tokens/API keys " +
        "are credentials like a password -- never share them, and regenerate them on the MCP server and " +
        "update them in GlucoSphere if you suspect they've been compromised."
        override val helpNightscoutDesc = "Blood glucose values, treatments, profiles, and statistics straight from your Nightscout instance."
        override val helpGlookoDesc = "No public, actively maintained Glooko MCP repository found (as of research on 2026-07-26). " +
        "If you know of or run one yourself, you can still enter it as a regular MCP server under " +
        "Settings -> Data sources."
        override val helpWithingsDesc = "Weight, body composition, sleep, activity via OAuth2 against the official Withings API. " +
        "Several independent community implementations -- check the README before use."
        override val helpWithingsAlt1 = "Alternative: Schimmilab/withings-mcp-server"
        override val helpWithingsAlt2 = "Alternative: davidmosiah/withings-mcp (local, tokens never leave the device)"
        override val helpWithingsAlt3 = "Withings Developer Portal (register your own OAuth2 app)"
        override val helpFeelfitDesc = "Body composition data from FeelFit scales (weight, body fat, muscle mass, etc.). " +
        "No direct GitHub repo found, only the following marketplace listings with setup instructions:"
        override val helpGoogleHealthDesc = "Comprehensive health-data access including the migration from Fitbit to the new " +
        "Google Health API -- supports photo-based food logging, among other things."
        override val helpGoogleHealthAlt = "Alternative: davidmosiah/google-health-mcp (local-first, Fitbit + Pixel Watch)"
        override val helpStravaTitle = "Strava MCP (sports / activity)"
        override val helpStravaDesc = "For pure sports/training data (activities, routes, segments, training history) as an " +
        "alternative/addition to Google Health, if sport is primarily tracked via Strava instead of " +
        "Fitbit/Health Connect. Several independent implementations -- check the README before use."
        override val helpStravaSettingsLink = "Strava API settings (register your own app)"
        override val helpOpenGithubRepo = "Open GitHub repository"
    },
    profileScreenStrings = object : ProfileScreenStrings {
        override val profileTitle = "Profile / User"
        override val profileUserTypeTitle = "User type"
        override val profileHint = "Determines the tone and focus of AI answers in Chat and the Overview -- the same data " +
        "is explained differently depending on the role."
        override val roleDiabetikerLabel = "Diabetic"
        override val roleDiabetikerDesc = "Personal, empathetic, practical -- everyday tips, blood glucose management, carb estimates."
        override val roleFachpersonalLabel = "Medical professional (diabetes care team)"
        override val roleFachpersonalDesc = "Clinically neutral, precise -- TIR, %CV, AGP profiles, insulin dosing, guideline compliance."
        override val roleAngehorigeLabel = "Family member (non-expert)"
        override val roleAngehorigeDesc = "Empathetic, reassuring, accessible -- recognizing emergency signs, clear action guidance."
    },
    viewModelChatTailStrings = object : ViewModelChatTailStrings {
        override val toolRunning = "running …"
        override val toolDeclined = "declined"
        override val toolCompleted = "completed"
        override val autoRoutedTo: (String) -> String = { model -> "Auto-routed to: $model" }
        override val pdfAnswerTitle = "GlucoSphere – Answer"
    },
)


fun stringsFor(language: AppLanguage): Strings = if (language == AppLanguage.GERMAN) StringsDe else StringsEn

/** Always provided at the app root (see `MainActivity.kt`'s `GlucoSphereApp`) -- the [StringsDe]
 * fallback here only matters for Compose previews or any composable accidentally rendered outside
 * that provider. */
val LocalStrings = staticCompositionLocalOf { StringsDe }

/** "⚡ 1.8s" -- same compact, language-neutral format wherever an LLM response duration is shown
 * (Übersicht "Letzter Stand" line and the Chat tab's per-answer status line), see item 5 of the
 * request that introduced [com.example.diabai.domain.analytics.DiabetesDashboard.generationDurationMillis]
 * / [ChatItem.AssistantMessage.durationMillis]. Null/non-positive durations (streaming still in
 * progress, or a cache/history entry with no recorded timing) simply render nothing. */
fun formatDurationBadge(millis: Long?): String? {
    if (millis == null || millis <= 0) return null
    return "⚡ %.1fs".format(millis / 1000.0)
}
