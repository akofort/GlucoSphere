package com.example.diabai.data

import kotlinx.serialization.Serializable

/**
 * "Einstellungen -> Profil / Benutzer": who is actually using the app, so the system prompt (see
 * [SettingsRepository]'s `personalizedSystemPrompt`/`anonymizedSystemPrompt` and
 * `rolePromptFor`) can adapt its tone and focus accordingly -- a diabetic managing their own
 * values, a clinician reviewing objective data, or a family member without medical background.
 * [DIABETIKER] is the default, matching the app's original single-audience behavior before this
 * setting existed.
 */
@Serializable
enum class UserRole(val label: String, val description: String) {
    DIABETIKER(
        "Diabetiker",
        "Persönlich, empathisch, praxisorientiert -- Alltagstipps, Blutzuckermanagement, KE/BE-Schätzungen.",
    ),
    FACHPERSONAL(
        "Medizinisches Fachpersonal (Diabetes-Team)",
        "Fachlich-neutral, präzise -- TIR, %CV, AGP-Profile, Insulindosierung, Leitlinien-Konformität.",
    ),
    ANGEHOERIGE(
        "Angehörige (Diabetes-Laien)",
        "Einfühlsam, beruhigend, barrierefrei -- Notfall-Signale erkennen, klare Handlungsempfehlungen.",
    ),
}
