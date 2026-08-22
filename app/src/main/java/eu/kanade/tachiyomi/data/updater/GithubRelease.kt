package eu.kanade.tachiyomi.data.updater

import android.os.Build
import dev.ahmedmohamed.hayai.update.HayaiReleasePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Release object.
 * Contains information about the latest release from GitHub.
 *
 * @param version version of latest release.
 * @param info log of latest release.
 * @param assets assets of latest release.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String,
    @SerialName("html_url") val releaseLink: String,
    @SerialName("prerelease") val preRelease: Boolean?,
    @SerialName("assets") private val assets: List<Assets>,
) {
    /**
     * Get download link of latest release from the assets.
     * @return download link of latest release.
     */
    val downloadLink: String
        get() {
            return HayaiReleasePolicy.selectApk(assets.map { it.downloadLink }, Build.SUPPORTED_ABIS[0])
        }

    /**
     * Assets class containing download url.
     * @param downloadLink download url.
     */
    @Serializable
    data class Assets(
        @SerialName("browser_download_url") val downloadLink: String,
    )
}
