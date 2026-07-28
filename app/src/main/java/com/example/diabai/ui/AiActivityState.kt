package com.example.diabai.ui

/** What the agent loop is doing right now, for the Chat tab's typing-indicator bubble (see
 * `ChatSection.kt`'s `TypingIndicatorBubble`) -- derived reactively in `GlucoSphereViewModel.sendMessage`
 * from the existing [com.example.diabai.domain.AgentEvent] stream as each event is collected, not
 * a separate signal the agent loop has to remember to emit on top of what it already reports. */
sealed interface AiActivityState {
    data object Idle : AiActivityState
    data object Thinking : AiActivityState
    data class CallingTool(val toolName: String, val serverName: String) : AiActivityState
    data object Generating : AiActivityState
}
