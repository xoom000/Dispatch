package dev.digitalgnosis.dispatch.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents information about an available app update.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val releaseDate: String,
    val mandatory: Boolean = false
)

/**
 * Response from GitHub API for latest release.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String,
    val body: String,
    @SerialName("published_at") val publishedAt: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    val url: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long
)
