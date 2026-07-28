package com.example.diabai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.diabai.data.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val sessionDateFormat = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)

/** Last-10 session list (see [com.example.diabai.data.MAX_CHAT_SESSIONS]) for quick re-entry into a chat. */
@Composable
fun ChatHistoryDrawerContent(
    sessions: List<ChatSession>,
    onNewChat: () -> Unit,
    onSelectSession: (ChatSession) -> Unit,
) {
    Column(Modifier.padding(12.dp)) {
        Text(
            text = "Chat-Verlauf",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        NavigationDrawerItem(
            label = { Text("+ Neuer Chat") },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        HorizontalDivider()
        if (sessions.isEmpty()) {
            Text(
                text = "Noch keine gespeicherten Chats",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        sessions.forEach { session ->
            NavigationDrawerItem(
                label = {
                    Text(
                        text = session.title.ifBlank { "Chat" },
                        maxLines = 1,
                    )
                },
                badge = { Text(sessionDateFormat.format(Date(session.updatedAtMillis))) },
                selected = false,
                onClick = { onSelectSession(session) },
                modifier = Modifier.fillMaxWidth().padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}
