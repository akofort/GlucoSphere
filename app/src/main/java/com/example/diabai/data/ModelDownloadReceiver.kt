package com.example.diabai.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Registered in the manifest (not dynamically) so it still fires and finishes the job even if
 * the app process was killed while DownloadManager kept working in the background. Moves the
 * finished file into app-private storage; [SettingsViewModel] picks up the resulting path
 * reactively and drives the "Modell einsatzbereit" status from there.
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (completedId == -1L) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleDownloadComplete(appContext, completedId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDownloadComplete(context: Context, downloadId: Long) {
        val settingsRepository = SettingsRepository(context)
        // Ignore broadcasts for ids we didn't start (or already finished handling).
        if (settingsRepository.settings.first().activeDownloadId != downloadId) return

        val modelFileManager = ModelFileManager(context)
        val status = modelFileManager.queryDownloadStatus(downloadId)
        settingsRepository.saveActiveDownloadId(null)

        val localFile = status?.localFile
        if (status?.state != ModelFileManager.DownloadState.SUCCESSFUL || localFile == null) {
            return
        }

        modelFileManager.adoptDownloadedFile(localFile)
            .onSuccess { finalFile -> settingsRepository.saveModelFilePath(finalFile.absolutePath) }
    }
}
