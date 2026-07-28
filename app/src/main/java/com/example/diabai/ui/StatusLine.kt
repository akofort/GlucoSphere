package com.example.diabai.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class StatusTone { NEUTRAL, SUCCESS, ERROR }

/** A small "spinner + colored text" row used across the settings screens for live status. */
@Composable
fun StatusLine(text: String, isBusy: Boolean, tone: StatusTone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = when (tone) {
                StatusTone.SUCCESS -> Color(0xFF2E7D32)
                StatusTone.ERROR -> MaterialTheme.colorScheme.error
                StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
