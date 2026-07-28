package com.example.diabai.domain

/**
 * Best-effort PII scrubber applied to outgoing text right before it leaves the device to a cloud
 * LLM provider (Gemini/Claude/OpenAI-OpenRouter) -- see [DiabetesAgent]'s call sites, which are
 * the only place this is invoked, and only when the active provider is *not* [LOCAL]. The local
 * on-device model never sends anything off the phone in the first place, so it always gets the
 * real, unmasked text/name for a proper personal address.
 *
 * Deliberately simple regex/keyword matching, not a real PII/NER model -- this runs synchronously
 * on every outgoing turn and has to stay cheap. It trades perfect recall for predictability: a
 * user's name and clearly date-shaped/email-shaped/phone-shaped substrings are masked; a bare age
 * ("42 Jahre") is intentionally left alone since it's not personally identifying on its own and
 * the app explicitly needs it for medical context in cloud answers.
 */
object PrivacyShield {
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // dd.mm.yyyy, dd.mm.yy, yyyy-mm-dd, dd/mm/yyyy -- deliberately only matches a full day/month/
    // year triple, so a bare age like "42 Jahre" or a plain glucose reading never matches.
    private val dateRegex = Regex("""\b(\d{1,2}[./]\d{1,2}[./]\d{2,4}|\d{4}-\d{2}-\d{2})\b""")

    // Requires at least 7 digit-like characters total (with optional spaces/separators) so short
    // numbers -- glucose readings, dosages, percentages -- are never caught by accident.
    private val phoneRegex = Regex("""(?<!\d)(\+?\d[\d\s/()-]{6,}\d)(?!\d)""")

    // "Musterstraße 12", "Am Markt 3a" -- a capitalized word ending in a common German street
    // suffix, followed by a house number. Best-effort, not exhaustive address/NER detection.
    private val addressRegex = Regex(
        """\b\p{Lu}[\p{L}]+(?:straße|strasse|weg|allee|platz|gasse)\s+\d+[a-zA-Z]?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Replaces [userName] (whole-word, case-insensitive) with `[USER]` and masks email/date/
     * phone/address-shaped substrings in [text] -- applied to the user's own free-form message
     * text before it's sent to a cloud provider. Never applied to MCP tool *results* (those are
     * real device-fetched clinical data the model needs intact to analyze correctly), only to
     * what the user themselves typed. */
    fun sanitize(text: String, userName: String): String {
        var result = text
        if (userName.isNotBlank()) {
            result = Regex("""\b${Regex.escape(userName.trim())}\b""", RegexOption.IGNORE_CASE)
                .replace(result, "[USER]")
        }
        result = emailRegex.replace(result, "[EMAIL]")
        result = dateRegex.replace(result, "[DATE]")
        result = addressRegex.replace(result, "[ADDRESS]")
        result = phoneRegex.replace(result, "[PHONE]")
        return result
    }

    private val placeholderTagRegex = Regex("""\[(USER|EMAIL|DATE|ADDRESS|PHONE)\]""")

    /** The reverse direction: a cloud provider's *own* system prompt is built from
     * [com.example.diabai.data.AppSettings.anonymizedSystemPrompt] (which literally contains
     * `[USER]` where the real name would go), so the model routinely echoes that exact tag back
     * verbatim in its greeting/answers ("Hallo [USER], ..."). Applied to every answer chunk
     * before it reaches the Compose UI (chat bubbles and the Übersicht summary/tips) so the raw
     * tag is never actually shown to the end user.
     *
     * [USER] is restored to the real, locally-stored first name -- the only placeholder with a
     * genuine local value to restore. [EMAIL]/[DATE]/[ADDRESS]/[PHONE] have no equivalent stored
     * profile field in this app (see [com.example.diabai.data.AppSettings] -- no address/phone/
     * birthdate field exists), so per the "clean removal" fallback these are simply dropped
     * rather than left as a raw tag; a run of now-orphaned spaces or dangling punctuation left
     * behind by the removal is collapsed/trimmed afterwards so the result still reads naturally. */
    fun unmask(text: String, userName: String): String {
        val restoredUser = userName.trim().ifBlank { "" }
        var result = placeholderTagRegex.replace(text) { match ->
            if (match.groupValues[1] == "USER") restoredUser else ""
        }
        // Collapse doubled-up whitespace and dangling " ," / " ." left behind by a removed tag
        // (e.g. "ruf mich unter  an." from a stripped "[PHONE]" -> "ruf mich unter an.").
        result = result.replace(Regex(""" {2,}"""), " ")
        result = result.replace(Regex(""" +([,.!?])"""), "$1")
        return result
    }
}
