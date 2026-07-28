package com.example.diabai.domain

import android.content.Context
import com.example.diabai.domain.llm.LLM_TEMPERATURE
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Bounds the local model's context window to a safe, fixed size -- LiteRT-LM's own
 * `EngineConfig.maxNumTokens` is the SDK-native equivalent of llama.cpp's `n_ctx`; this project
 * has no C++/JNI layer of its own to tune directly (LiteRT-LM ships a prebuilt Kotlin AAR, see
 * this class's own doc comment below), so this is the actual lever available for "n_ctx auf
 * sichere 2048 bis max. 4096 Tokens". */
private const val LOCAL_MODEL_MAX_CONTEXT_TOKENS = 4096

/** Deterministic sampler settings for the local model -- same reasoning as the cloud providers'
 * [LLM_TEMPERATURE]: medical/glycemic figures need factual precision, not creative variety.
 * topK/topP are set to identical middle-of-the-road values, only temperature actually drives
 * that goal. */
private val LOCAL_MODEL_SAMPLER_CONFIG = SamplerConfig(40, 0.9, LLM_TEMPERATURE, 0)

/**
 * Loads a local Gemma model (`.litertlm`) via Google's LiteRT-LM SDK and runs it on the
 * device GPU (falls back through whatever LiteRT-LM itself selects if the GPU delegate is
 * unavailable). Replaces the previous llama.cpp/JNI engine -- LiteRT-LM ships a ready Kotlin
 * AAR, so there's no custom native module to build/maintain here anymore.
 */
class LiteRtInferenceEngine(context: Context) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var engine: Engine? = null

    suspend fun loadModel(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(modelFile.exists()) { "Model file does not exist: ${modelFile.absolutePath}" }
            unloadLocked()
            _state.value = EngineState.LoadingModel

            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                cacheDir = appContext.cacheDir.path,
                maxNumTokens = LOCAL_MODEL_MAX_CONTEXT_TOKENS,
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            _state.value = EngineState.Ready(modelFile.absolutePath)
        }.onFailure { e ->
            engine?.close()
            engine = null
            _state.value = EngineState.Error(e.message ?: "Unknown error loading model")
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) { unloadLocked() }

    /**
     * Starts a fresh conversation configured with [personaPrompt] as the system instruction
     * and [tools] registered for manual tool calling (the caller -- [DiabetesAgent] -- decides
     * whether/when to actually execute a requested tool, so `automaticToolCalling` is off).
     * Returns null if no model is loaded.
     */
    fun createConversation(personaPrompt: String, tools: List<OpenApiTool> = emptyList()): Conversation? {
        val currentEngine = engine ?: return null
        val config = ConversationConfig(
            systemInstruction = Contents.of(personaPrompt),
            tools = tools.map { tool(it) },
            samplerConfig = LOCAL_MODEL_SAMPLER_CONFIG,
            automaticToolCalling = false,
        )
        return currentEngine.createConversation(config)
    }

    private fun unloadLocked() {
        engine?.close()
        engine = null
        _state.value = EngineState.Idle
    }
}
