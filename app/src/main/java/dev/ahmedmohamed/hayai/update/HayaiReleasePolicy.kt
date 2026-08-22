package dev.ahmedmohamed.hayai.update

enum class HayaiReleaseChannel(val repository: String) {
    STABLE("HayaiApp/hayai"),
    NIGHTLY("HayaiApp/hayai-nightly"),
}

data class HayaiBuildRelease(
    val channel: HayaiReleaseChannel,
    val currentVersion: String,
    val currentNightlyNumber: Long?,
) {
    val repository: String = channel.repository
    val releaseTag: String =
        when (channel) {
            HayaiReleaseChannel.STABLE -> "v$currentVersion"
            HayaiReleaseChannel.NIGHTLY -> "r${requireNotNull(currentNightlyNumber)}"
        }

    fun isNewer(candidateTag: String): Boolean = HayaiReleasePolicy.isNewer(channel, candidateTag, releaseTag)
}

object HayaiReleasePolicy {
    fun current(
        nightly: Boolean,
        versionName: String,
        buildNumber: String,
    ): HayaiBuildRelease =
        if (nightly) {
            val nightlyNumber = buildNumber.toLongOrNull() ?: nightlyNumber(versionName)
            requireNotNull(nightlyNumber) { "A nightly build requires a numeric build number." }
            HayaiBuildRelease(HayaiReleaseChannel.NIGHTLY, versionName, nightlyNumber)
        } else {
            HayaiBuildRelease(HayaiReleaseChannel.STABLE, versionName, null)
        }

    fun selectApk(
        downloadLinks: List<String>,
        primaryAbi: String,
    ): String {
        require(downloadLinks.isNotEmpty()) { "The release has no assets." }
        val abiSuffix =
            when (primaryAbi) {
                "arm64-v8a" -> "-arm64-v8a"
                "armeabi-v7a" -> "-armeabi-v7a"
                "x86" -> "-x86"
                "x86_64" -> "-x86_64"
                else -> ""
            }
        val apkLinks = downloadLinks.filter { it.substringAfterLast('/').endsWith(".apk", ignoreCase = true) }
        return apkLinks.firstOrNull { it.substringAfterLast('/').startsWith("hayai$abiSuffix-") }
            ?: apkLinks.firstOrNull {
                val name = it.substringAfterLast('/')
                name.startsWith("hayai-") &&
                    listOf("hayai-arm64-v8a-", "hayai-armeabi-v7a-", "hayai-x86-", "hayai-x86_64-")
                        .none(name::startsWith)
            }
            ?: error("The release has no Hayai APK for $primaryAbi.")
    }

    fun isNewer(
        channel: HayaiReleaseChannel,
        candidateTag: String,
        currentTag: String,
    ): Boolean =
        when (channel) {
            HayaiReleaseChannel.STABLE -> isNewStableVersion(candidateTag, currentTag)
            HayaiReleaseChannel.NIGHTLY -> {
                val candidate = nightlyNumber(candidateTag)
                val current = nightlyNumber(currentTag)
                candidate != null && current != null && candidate > current
            }
        }
}

private data class StableVersion(
    val numbers: List<Int>,
    val prereleaseNumber: Int?,
)

private fun isNewStableVersion(candidateTag: String, currentVersion: String): Boolean {
    val candidate = stableVersion(candidateTag) ?: return false
    val current = stableVersion(currentVersion) ?: return false
    val width = maxOf(candidate.numbers.size, current.numbers.size)
    repeat(width) { index ->
        val newPart = candidate.numbers.getOrElse(index) { 0 }
        val oldPart = current.numbers.getOrElse(index) { 0 }
        if (newPart != oldPart) return newPart > oldPart
    }
    return when {
        current.prereleaseNumber == null -> false
        candidate.prereleaseNumber == null -> true
        else -> candidate.prereleaseNumber > current.prereleaseNumber
    }
}

private fun stableVersion(value: String): StableVersion? {
    val match = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-[A-Za-z]+(\\d+))?$").matchEntire(value) ?: return null
    val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    return StableVersion(numbers, match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull())
}

private fun nightlyNumber(value: String): Long? =
    Regex("(?:^r|.*-r)(\\d+)$").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
