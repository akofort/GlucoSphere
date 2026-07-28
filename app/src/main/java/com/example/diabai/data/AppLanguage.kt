package com.example.diabai.data

import kotlinx.serialization.Serializable

/**
 * The app's UI/voice language -- Deutsch or English. [languageTag] is a BCP-47 tag used both for
 * [android.app.LocaleManager.setApplicationLocales] (see `GlucoSphereApp`'s wiring in MainActivity, the
 * modern per-app language API) and for STT/TTS locale selection (see `ui/SpeechUtils.kt`).
 *
 * Scope note: this app has no `strings.xml`/resource-based UI text at all -- every screen's
 * labels are literal German string constants directly in its Composable. Switching this setting
 * therefore does NOT retranslate the UI chrome (that would mean extracting several hundred
 * hardcoded strings across every screen into resources, a large, separate, testable effort of its
 * own). What it DOES drive end-to-end: the Übersicht tab's Ampel-Label ("Grün/Gelb/Rot" vs.
 * "Green/Yellow/Red", including what's sent to the LLM -- see [TrafficLightStatus]) and the STT/
 * TTS locale. Treat this as real, working language infrastructure with one fully-localized
 * feature wired to it, not a claim that the whole app is bilingual yet.
 */
@Serializable
enum class AppLanguage(val languageTag: String, val displayName: String) {
    GERMAN("de-DE", "Deutsch"),
    ENGLISH("en-US", "English"),
}
