package com.example.diabai.domain

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** OWASP's current minimum recommendation for PBKDF2-HMAC-SHA256 (as of 2023's revision) -- high
 * enough to make offline password guessing against a stolen export genuinely slow, while still
 * completing in well under a second on-device for the legitimate export/import path. */
private const val PBKDF2_ITERATIONS = 210_000
private const val KEY_LENGTH_BITS = 256
private const val SALT_LENGTH_BYTES = 16
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

/**
 * AES-256-GCM (authenticated encryption -- a wrong password or any tampering fails the decrypt
 * outright via the GCM tag check, it can never silently return corrupted plaintext) with a
 * PBKDF2WithHmacSHA256-derived key, per "Export- und Importfunktion für Einstellungen (mit
 * optionaler AES-Verschlüsselung)" item 2. Both primitives are built into the Android/JDK
 * `javax.crypto` stack -- no extra Gradle dependency needed.
 */
private object BackupCrypto {
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Output layout: `[salt(16)][iv(12)][ciphertext+tag]` -- salt and IV are freshly randomized
     * on every call (never reused across exports), so two exports of the identical plaintext under
     * the same password still produce completely different bytes. */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also(random::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return salt + iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt]. Throws [GeneralSecurityException] (caught by [SettingsBackupFile.read])
     * on a wrong password or corrupted/tampered data. */
    fun decrypt(payload: ByteArray, password: String): ByteArray {
        require(payload.size > SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES) { "Payload zu kurz" }
        val salt = payload.copyOfRange(0, SALT_LENGTH_BYTES)
        val iv = payload.copyOfRange(SALT_LENGTH_BYTES, SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}

private val MAGIC = byteArrayOf('D'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte())
private const val FORMAT_VERSION: Byte = 1
private const val FLAG_PLAIN: Byte = 0
private const val FLAG_ENCRYPTED: Byte = 1
private const val HEADER_LENGTH = 6 // 4 magic + 1 format version + 1 encrypted flag

/**
 * The actual `.diabai` export file container: `[4 bytes magic "DIAB"][1 byte format version]
 * [1 byte encrypted flag][payload]`. The flag byte is what lets [read] tell -- BEFORE attempting
 * any decryption or JSON parsing -- whether a password is even needed at all, so the "elegant
 * password dialog" from item 2 only ever appears for files that actually are encrypted, never for
 * a plain export.
 */
object SettingsBackupFile {
    /** [password] null -> plain JSON payload; non-null -> [BackupCrypto]-encrypted payload. */
    fun write(json: String, password: String?): ByteArray {
        val flag = if (password != null) FLAG_ENCRYPTED else FLAG_PLAIN
        val header = MAGIC + byteArrayOf(FORMAT_VERSION, flag)
        val payload = if (password != null) {
            BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password)
        } else {
            json.toByteArray(Charsets.UTF_8)
        }
        return header + payload
    }

    sealed interface ReadResult {
        data class Plain(val json: String) : ReadResult

        /** [bytes] is unchanged from the [read] call that returned this -- callers hold onto it
         * and pass it back into a follow-up [read] call once the user has supplied a password,
         * rather than needing to re-read the source file/URI a second time. */
        data object PasswordRequired : ReadResult
        data object WrongPassword : ReadResult
        data class InvalidFile(val message: String) : ReadResult
    }

    /** [password] should be null on the very first attempt for any given file (before the caller
     * knows whether it's even encrypted) -- returns [ReadResult.PasswordRequired] in that case
     * rather than failing, so the UI can show the password dialog and call this again with it. */
    fun read(bytes: ByteArray, password: String?): ReadResult {
        if (bytes.size < HEADER_LENGTH || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            return ReadResult.InvalidFile("Keine gültige GlucoSphere-Backup-Datei")
        }
        if (bytes[4] != FORMAT_VERSION) {
            return ReadResult.InvalidFile("Nicht unterstützte Backup-Dateiversion")
        }
        val payload = bytes.copyOfRange(HEADER_LENGTH, bytes.size)
        if (bytes[5] == FLAG_PLAIN) {
            return ReadResult.Plain(payload.toString(Charsets.UTF_8))
        }
        if (password == null) return ReadResult.PasswordRequired
        return try {
            ReadResult.Plain(BackupCrypto.decrypt(payload, password).toString(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            ReadResult.WrongPassword
        } catch (e: IllegalArgumentException) {
            ReadResult.InvalidFile(e.message ?: "Beschädigte Backup-Datei")
        }
    }
}
