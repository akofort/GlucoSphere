package com.example.diabai.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** [start] launches the system speech-recognition dialog; [isListening] flips true right when
 * [start] is called and back to false once a result (or a cancel/error) comes back -- the actual
 * "Höre zu …" visual feedback lives at the call site (see `ChatSection.kt`), this only tracks
 * the state driving it. There's no intermediate "recognizer is actively listening" callback on
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] itself (the system dialog owns that UI on its own),
 * so this approximates it as "between launch and result". */
class SpeechInputState internal constructor(private val listening: MutableState<Boolean>) {
    val isListening: State<Boolean> get() = listening
    internal fun setListening(value: Boolean) {
        listening.value = value
    }
}

/**
 * Native Android speech-to-text via [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] -- delegates to
 * the system speech-recognition activity (Google app on virtually every device) rather than
 * hand-rolling the lower-level `SpeechRecognizer`/`RecognitionListener` state machine: the system
 * dialog already owns the mic UI, silence detection, and its own permission handling, so this is
 * the simpler and more robust of the two APIs the user explicitly allowed ("SpeechRecognizer
 * (oder RecognizerIntent)").
 *
 * Returns a [SpeechInputState] (for the "Höre zu …" indicator) plus a plain `() -> Unit` to call
 * from a mic button's `onClick`; [onResult] fires with the top recognition candidate once the
 * user finishes speaking. A missing speech-recognition app (rare, but possible on AOSP-only
 * builds) fails silently rather than crashing -- there's nothing actionable to show the user
 * beyond the mic button visibly doing nothing.
 */
@Composable
fun rememberSpeechInputLauncher(languageTag: String, prompt: String = "Sprechen …", onResult: (String) -> Unit): Pair<SpeechInputState, () -> Unit> {
    val state = remember { SpeechInputState(mutableStateOf(false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        state.setListening(false)
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (text != null) onResult(text)
        }
    }
    val start: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            state.setListening(true)
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // No speech-recognition app installed -- nothing to recover into, the mic button
            // just doesn't do anything this time rather than crashing the chat screen over it.
            state.setListening(false)
        }
    }
    return state to start
}
