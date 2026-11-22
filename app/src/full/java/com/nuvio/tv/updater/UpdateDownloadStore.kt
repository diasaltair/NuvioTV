package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val downloadMutex = Mutex()

    fun destinationFor(update: AppUpdate): File {
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(updatesDir, safeName)
    }

    /** Returns the already downloaded APK for this release, or null when it is missing or incomplete. */
    suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path, code) = updatePreferences.downloadedUpdate.first() ?: return null
        if (tag != update.tag || code != update.versionCode) return null
        val file = File(path)
        val expectedSize = update.assetSizeBytes
        return file.takeIf { it.exists() && (expectedSize == null || it.length() == expectedSize) }
    }

    /** Returns the downloaded APK when it is for a version newer than the installed one. */
    suspend fun pendingInstall(): PendingInstall? {
        val downloaded = updatePreferences.downloadedUpdate.first() ?: return null
        val file = File(downloaded.apkPath)
        if (!file.exists() || !downloaded.isNewerThanInstalled()) return null
        return PendingInstall(downloaded.tag, file, downloaded.versionCode)
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        val destination = destinationFor(update)
        apkDownloader.download(update.assetUrl, destination, onProgress)
            .onSuccess { file ->
                updatesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath, update.versionCode)
            }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    suspend fun clear() {
        updatesDir.deleteRecursively()
        updatePreferences.setDownloadedUpdate(null, null)
    }

    suspend fun clearIfInstalled() {
        val downloaded = updatePreferences.downloadedUpdate.first() ?: return
        if (!downloaded.isNewerThanInstalled()) clear()
    }
}

data class DownloadedUpdate(val tag: String, val apkPath: String, val versionCode: Long? = null) {
    /** By version code when the release had one, as the update check decides; by name otherwise. */
    fun isNewerThanInstalled(
        installedName: String = BuildConfig.VERSION_NAME,
        installedCode: Long = BuildConfig.VERSION_CODE.toLong()
    ): Boolean = VersionUtils.isRemoteNewer(tag, installedName, versionCode, installedCode)
}

data class PendingInstall(val tag: String, val file: File, val versionCode: Long?)
