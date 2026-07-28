package com.example.diabai.domain

import kotlinx.serialization.json.JsonObject

sealed interface EngineState {
    data object Idle : EngineState
    data object LoadingModel : EngineState
    data class Ready(val modelPath: String) : EngineState
    data object Generating : EngineState
    data class Error(val message: String) : EngineState
}

/** A tool invocation the model requested (surfaced via LiteRT-LM's structured tool calling). */
data class ToolCall(val name: String, val arguments: JsonObject)
