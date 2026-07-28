package com.example.diabai.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?)

/**
 * Model files (`.litertlm`) either get picked via the Storage Access Framework (which only
 * hands out a `content://` URI) or downloaded via Android's own [DownloadManager] -- either
 * way LiteRT-LM needs a real filesystem path, so the result is copied/moved once into
 * app-private storage and that path is what gets persisted/loaded from then on.
 */
class ModelFileManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager: DownloadManager
        get() = appContext.getSystemService<DownloadManager>() ?: error("DownloadManager nicht verfügbar")

    private val modelsDir: File
        get() = File(appContext.filesDir, "models").apply { mkdirs() }

    suspend fun importModel(sourceUri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(sourceUri)?.takeIf { it.isNotBlank() } ?: "model.litertlm"
            val target = File(modelsDir, fileName)
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Datei konnte nicht geöffnet werden")
            target
        }
    }

    /**
     * Hands [url] to the system DownloadManager: it runs as an OS-level background service
     * (survives this app being killed), shows its own progress notification, and resumes
     * automatically on transient network loss using HTTP range requests -- none of which a
     * hand-rolled in-process download gets for free. Returns the DownloadManager request id;
     * [ModelDownloadReceiver] picks up completion via `ACTION_DOWNLOAD_COMPLETE`.
     */
    fun enqueueDownload(url: String): Long {
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "model.litertlm" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("GlucoSphere-Modell wird heruntergeladen")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return downloadManager.enqueue(request)
    }

    enum class DownloadState { PENDING, RUNNING, SUCCESSFUL, FAILED }

    data class DownloadStatus(
        val state: DownloadState,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val reason: String?,
        val localFile: File?,
    )

    /** Null means DownloadManager no longer knows this id (already handled/cleared). */
    fun queryDownloadStatus(downloadId: Long): DownloadStatus? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reasonCode = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localFile = if (localUriIndex >= 0) {
                cursor.getString(localUriIndex)?.let { Uri.parse(it).path?.let(::File) }
            } else {
                null
            }

            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> DownloadState.RUNNING
                else -> DownloadState.PENDING
            }

            return DownloadStatus(
                state = state,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes.takeIf { it > 0 },
                reason = if (state == DownloadState.FAILED) "Code $reasonCode" else null,
                localFile = localFile,
            )
        }
        return null
    }

    /** Moves a DownloadManager-produced file into app-private storage, where LiteRT-LM reads from. */
    suspend fun adoptDownloadedFile(source: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(source.exists()) { "Heruntergeladene Datei fehlt: ${source.absolutePath}" }
            val target = File(modelsDir, source.name)
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }
}
