package dev.digitalgnosis.dispatch.update

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
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val published_at: String,
    val assets: List<GitHubAsset>
)

data class GitHubAsset(
    val name: String,
    val url: String,
    val browser_download_url: String,
    val size: Long
)
