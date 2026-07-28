package com.example.diabai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/** Returns a click callback that opens the system file picker for any file type and forwards the picked URI. */
@Composable
fun rememberFilePickerLauncher(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(onPicked)
    }
    return { launcher.launch(arrayOf("*/*")) }
}
