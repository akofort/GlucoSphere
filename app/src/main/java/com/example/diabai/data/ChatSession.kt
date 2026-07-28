package com.example.diabai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

const val MAX_CHAT_SESSIONS = 10

enum class StoredRole { USER, ASSISTANT }

@Serializable
data class StoredMessage(val role: StoredRole, val text: String)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val title: String = "",
    val messages: List<StoredMessage> = emptyList(),
)

private val sessionListJson = Json { ignoreUnknownKeys = true }

fun List<ChatSession>.encodeToJson(): String = sessionListJson.encodeToString(this)

fun String.decodeChatSessions(): List<ChatSession> =
    if (isBlank()) {
        emptyList()
    } else {
        runCatching { sessionListJson.decodeFromString<List<ChatSession>>(this) }.getOrElse { emptyList() }
    }

/** Inserts/updates [session] by id, newest first, trimmed to [MAX_CHAT_SESSIONS]. */
fun List<ChatSession>.upsert(session: ChatSession): List<ChatSession> =
    (listOf(session) + filterNot { it.id == session.id })
        .sortedByDescending { it.updatedAtMillis }
        .take(MAX_CHAT_SESSIONS)
