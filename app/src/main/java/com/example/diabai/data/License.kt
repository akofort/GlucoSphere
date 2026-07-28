package com.example.diabai.data

import java.security.MessageDigest

/**
 * Local license tiers (item 1) -- there is no backend/license server for this app (it's a
 * purely on-device, no-account tool), so [LicenseKeyValidator] can only ever check a key's shape
 * against a locally-embedded checksum, not against a real issuing authority. This is a soft,
 * good-faith gate (any key format is trivially recoverable from the APK by someone determined to
 * look), NOT real DRM -- appropriate for gating optional developer conveniences and a fair-use
 * daily-time nudge, not for protecting paid content.
 */
enum class LicenseTier(val label: String) {
    /** Default when no (or an invalid) license key is stored -- "Keine Lizenz". */
    FREE("Unlizenziert (Free)"),
    TEST("Test-Lizenz"),
    USER("User-Lizenz"),
    DEVELOPER("Entwickler-Lizenz"),
}

/** Daily active-usage cap per [LicenseTier] -- null means unlimited. See
 * [AppSettings.isDailyUsageLimitReached]/[AppSettings.dailyUsageLimitMillis]. */
fun LicenseTier.dailyLimitMillis(): Long? = when (this) {
    LicenseTier.FREE -> 10 * 60_000L
    LicenseTier.TEST -> 15 * 60_000L
    LicenseTier.USER, LicenseTier.DEVELOPER -> null
}

/**
 * Validates a locally-entered license key against a fixed, embedded checksum -- format
 * `GLUCOSPHERE-<TIER>-<8-HEX-CHECKSUM>`, e.g. `GLUCOSPHERE-DEV-46DAC428`. The checksum is
 * `SHA-256("$SALT:$tierName")` truncated to its first 8 hex chars (uppercase) -- deterministic
 * and offline-checkable, no network round trip needed to unlock a tier.
 */
object LicenseKeyValidator {
    private const val SALT = "GlucoSphere-License-Salt-2026"

    private val tierCodes = mapOf(
        "DEV" to LicenseTier.DEVELOPER,
        "USER" to LicenseTier.USER,
        "TEST" to LicenseTier.TEST,
    )

    /** Null (falls back to [LicenseTier.FREE] at the call site) for a blank, malformed, or
     * checksum-mismatched key -- never throws, so a stray typo just silently stays on Free
     * rather than crashing the settings screen. */
    fun validate(rawKey: String): LicenseTier? {
        val parts = rawKey.trim().uppercase().split("-")
        if (parts.size != 3 || parts[0] != "GLUCOSPHERE") return null
        val tier = tierCodes[parts[1]] ?: return null
        return if (parts[2] == checksumFor(tier)) tier else null
    }

    private fun checksumFor(tier: LicenseTier): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$SALT:${tier.name}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }.take(8)
    }
}
