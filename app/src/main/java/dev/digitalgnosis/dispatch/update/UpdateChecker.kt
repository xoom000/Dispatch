package dev.digitalgnosis.dispatch.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for app updates from GitHub releases and handles APK download/installation.
 *
 * Public repo — no auth needed. The GitHub Releases API is openly accessible.
 * Only users who already have the app installed can trigger the update flow.
 * Closed distribution: you get the first install from someone, then self-update.
 */
class UpdateChecker(
    private val context: Context,
    private val githubUsername: String = GITHUB_USERNAME,
    private val repoName: String = REPO_NAME,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks GitHub releases for a newer version than currently installed.
     * @return UpdateInfo if update available, null otherwise
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersionCode = getCurrentVersionCode()
            val latestRelease = fetchLatestRelease()

            if (latestRelease == null) {
                Timber.w("UpdateChecker: no releases found on GitHub")
                return@withContext null
            }

            val latestVersionCode = extractVersionCodeFromBody(latestRelease.body)
                ?: parseVersionCode(latestRelease.tagName)

            Timber.d("UpdateChecker: current=%d, latest=%d (tag=%s)",
                currentVersionCode, latestVersionCode, latestRelease.tagName)

            if (latestVersionCode > currentVersionCode) {
                val apkAsset = latestRelease.assets.find {
                    it.name.endsWith(".apk", ignoreCase = true)
                }

                if (apkAsset == null) {
                    Timber.w("UpdateChecker: no APK in release %s", latestRelease.tagName)
                    return@withContext null
                }

                UpdateInfo(
                    versionCode = latestVersionCode,
                    versionName = latestRelease.tagName.removePrefix("v"),
                    downloadUrl = apkAsset.browserDownloadUrl,
                    releaseNotes = latestRelease.body
                        .replace(Regex("<!--.*?-->"), "")
                        .trim()
                        .ifEmpty { "No release notes provided" },
                    releaseDate = latestRelease.publishedAt,
                    mandatory = false
                )
            } else {
                Timber.d("UpdateChecker: app is up to date")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "UpdateChecker: failed to check for updates")
            null
        }
    }

    /**
     * Downloads APK and triggers installation.
     */
    suspend fun downloadAndInstall(updateInfo: UpdateInfo) = withContext(Dispatchers.IO) {
        try {
            Timber.d("UpdateChecker: downloading %s from %s",
                updateInfo.versionName, updateInfo.downloadUrl)

            val apkFile = File(context.cacheDir, "update-${updateInfo.versionName}.apk")

            val url = URL(updateInfo.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/octet-stream")

            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()

            Timber.d("UpdateChecker: APK downloaded to %s (%d bytes)",
                apkFile.absolutePath, apkFile.length())

            installApk(apkFile)

        } catch (e: Exception) {
            Timber.e(e, "UpdateChecker: failed to download/install update")
            throw UpdateException("Failed to download update: ${e.message}", e)
        }
    }

    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }

    private fun fetchLatestRelease(): GitHubRelease? {
        return try {
            val url = URL("https://api.github.com/repos/$githubUsername/$repoName/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            if (connection.responseCode != 200) {
                Timber.w("UpdateChecker: GitHub API returned %d", connection.responseCode)
                connection.disconnect()
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            json.decodeFromString<GitHubRelease>(responseBody)
        } catch (e: Exception) {
            Timber.e(e, "UpdateChecker: failed to fetch GitHub release")
            null
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            }
        } catch (e: Exception) {
            Timber.e(e, "UpdateChecker: failed to get version code")
            1
        }
    }

    private fun extractVersionCodeFromBody(body: String): Int? {
        return try {
            val regex = """<!--\s*versionCode:\s*(\d+)\s*-->""".toRegex()
            regex.find(body)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) { null }
    }

    private fun parseVersionCode(tagName: String): Int {
        return try {
            val version = tagName.removePrefix("v")
            val parts = version.split(".")
            when (parts.size) {
                3 -> parts[2].toIntOrNull() ?: 0
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    companion object {
        const val GITHUB_USERNAME = "xoom000"
        const val REPO_NAME = "Dispatch"
    }
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
