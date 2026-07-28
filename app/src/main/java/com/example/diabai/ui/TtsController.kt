package com.example.diabai.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.diabai.data.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * One shared [TextToSpeech] engine for the whole app (see `MainTabsScreen`, which owns and
 * disposes this) -- Chat's assistant bubbles and the Übersicht tab's "Zusammenfassung" card both
 * read through here rather than each spinning up their own engine instance. [isSpeakingId] tracks
 * *which* item is currently being read (by whatever id the caller passed to [speak]) so a
 * bubble/card can show "⏹ stop" only for the one actually playing, not every one at once.
 */
class TtsController(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var pendingLocale: Locale? = null

    private val _isSpeakingId = MutableStateFlow<String?>(null)
    val isSpeakingId: StateFlow<String?> = _isSpeakingId.asStateFlow()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                pendingLocale?.let { tts?.language = it }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (_isSpeakingId.value == utteranceId) _isSpeakingId.value = null
            }

            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                if (_isSpeakingId.value == utteranceId) _isSpeakingId.value = null
            }
        })
    }

    fun setLanguage(language: AppLanguage) {
        val locale = if (language == AppLanguage.GERMAN) Locale.GERMANY else Locale.US
        pendingLocale = locale
        tts?.language = locale
    }

    /** Toggle: speaking [id] again while it's already the one playing stops it instead (matches
     * "Ein erneuter Klick ... stoppt die Sprachausgabe sofort"). Starting a *different* [id]
     * implicitly stops whatever was playing first -- [TextToSpeech.QUEUE_FLUSH] handles that. */
    fun speak(id: String, rawText: String) {
        if (_isSpeakingId.value == id) {
            stop()
            return
        }
        val clean = stripMarkdownForSpeech(rawText)
        if (clean.isBlank()) return
        _isSpeakingId.value = id
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        tts?.stop()
        _isSpeakingId.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/** Strips Markdown syntax (headings, bold/italic/code markers, table pipes/separators) and
 * `<think>` reasoning blocks out of [text] before it's handed to TTS -- read literally, "**TIR**"
 * or a `| col | col |` table row sounds like nonsense read aloud otherwise. */
fun stripMarkdownForSpeech(text: String): String = text
    .replace(Regex("(?s)<think>.*?</think>"), " ")
    .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("[*`_]"), "")
    .replace(Regex("^\\|?[-:| ]+\\|?$", RegexOption.MULTILINE), " ")
    .replace('|', ' ')
    .replace(Regex("^[-•]\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("\\n{2,}"), ". ")
    .replace('\n', ' ')
    .replace(Regex("\\s{2,}"), " ")
    .trim()
