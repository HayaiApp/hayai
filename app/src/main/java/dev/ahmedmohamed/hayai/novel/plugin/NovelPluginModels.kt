package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

@Serializable
data class NovelPluginDescriptor(
    val id: String,
    val name: String,
    val site: String = "",
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String = "",
    val customCSS: String? = null,
    val customJS: String? = null,
    val sha256: String? = null,
    val signingKey: String? = null,
    val signature: String? = null,
) {
    fun validate(repositoryUrl: String): NovelPluginDescriptor {
        novelRequire(ID_PATTERN.matches(id), NovelFailure.Code.PluginId)
        novelRequire(name.isNotBlank() && name.length <= 256, NovelFailure.Code.PluginName)
        novelRequire(lang.isNotBlank() && lang.length <= 64, NovelFailure.Code.PluginLanguage)
        novelRequire(version.isNotBlank() && version.length <= 64, NovelFailure.Code.PluginVersion)
        requireSafeUrl(repositoryUrl, allowLocalHttp = true)
        resolvePluginUrl(repositoryUrl, url)
        if (site.isNotBlank()) requireContentUrl(site)
        if (iconUrl.isNotBlank()) resolvePluginUrl(repositoryUrl, iconUrl)
        sha256?.let { novelRequire(SHA256_PATTERN.matches(it), NovelFailure.Code.PluginSha256) }
        novelRequire((signingKey == null) == (signature == null), NovelFailure.Code.PluginSigningPair)
        signingKey?.let { novelRequire(decodeBase64(it).size in 32..128, NovelFailure.Code.PluginPublicKey) }
        signature?.let { novelRequire(decodeBase64(it).size == 64, NovelFailure.Code.PluginSignature) }
        novelRequire((customCSS?.length ?: 0) <= 100_000, NovelFailure.Code.PluginCssTooLarge)
        novelRequire((customJS?.length ?: 0) <= 100_000, NovelFailure.Code.PluginJavaScriptTooLarge)
        return this
    }

    fun sourceId(): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest("hayai-novel-plugin:$id".toByteArray())
        return (0..7).fold(0L) { value, index -> (value shl 8) or (digest[index].toLong() and 0xff) } and Long.MAX_VALUE
    }

    fun normalizedLanguage(): String =
        LANGUAGE_ALIASES[lang.trim().lowercase()]
            ?: lang.trim().lowercase().takeIf { ISO_LANGUAGE.matches(it) }
            ?: "other"

    fun resolvedCodeUrl(repositoryUrl: String): String = resolvePluginUrl(repositoryUrl, url)

    fun resolvedIconUrl(repositoryUrl: String): String? = iconUrl.takeIf(String::isNotBlank)?.let { resolvePluginUrl(repositoryUrl, it) }

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
        private val ISO_LANGUAGE = Regex("[a-z]{2,3}")
        private fun decodeBase64(value: String): ByteArray = runCatching { java.util.Base64.getDecoder().decode(value) }.getOrElse { throw NovelFailure(NovelFailure.Code.PluginBase64, cause = it) }
        private val LANGUAGE_ALIASES =
            mapOf(
                "english" to "en",
                "chinese" to "zh",
                "中文" to "zh",
                "japanese" to "ja",
                "日本語" to "ja",
                "korean" to "ko",
                "한국어" to "ko",
                "french" to "fr",
                "français" to "fr",
                "spanish" to "es",
                "español" to "es",
                "portuguese" to "pt",
                "português" to "pt",
                "russian" to "ru",
                "русский" to "ru",
                "indonesian" to "id",
                "indonesia" to "id",
                "turkish" to "tr",
                "türkçe" to "tr",
                "arabic" to "ar",
                "العربية" to "ar",
                "thai" to "th",
                "ไทย" to "th",
                "vietnamese" to "vi",
                "việt" to "vi",
                "polish" to "pl",
                "polski" to "pl",
                "ukrainian" to "uk",
                "українська" to "uk",
                "multi" to "all",
            )
    }
}

@Serializable
data class InstalledNovelPlugin(
    val descriptor: NovelPluginDescriptor,
    val repositoryUrl: String,
    val installedAt: Long,
    val codeSha256: String,
    val codeFile: String = "${descriptor.id}-$codeSha256.js",
)

data class NovelPluginRepository(
    val name: String,
    val url: String,
    val enabled: Boolean,
)

internal fun requireSafeUrl(
    value: String,
    allowLocalHttp: Boolean,
) {
    novelRequire(value.length in 1..8_192, NovelFailure.Code.PluginUrlLength)
    val uri = runCatching { URI(value) }.getOrNull()
    novelRequire(uri?.isAbsolute == true && uri.host != null && uri.userInfo == null, NovelFailure.Code.PluginAbsoluteUrl)
    val scheme = uri.scheme.lowercase()
    val local = uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1"
    novelRequire(scheme == "https" || (allowLocalHttp && scheme == "http" && local), NovelFailure.Code.PluginHttpsOnly)
}

private fun requireContentUrl(value: String) {
    novelRequire(value.length in 1..8_192, NovelFailure.Code.PluginUrlLength)
    val uri = runCatching { URI(value) }.getOrNull()
    novelRequire(uri?.isAbsolute == true && uri.host != null && uri.userInfo == null, NovelFailure.Code.PluginWebsiteUrl)
    novelRequire(uri.scheme.equals("https", true) || uri.scheme.equals("http", true), NovelFailure.Code.PluginWebsiteScheme)
}

internal fun resolvePluginUrl(
    repositoryUrl: String,
    value: String,
): String {
    requireSafeUrl(repositoryUrl, allowLocalHttp = true)
    novelRequire(value.length in 1..8_192, NovelFailure.Code.PluginUrlLength)
    val base = URI(repositoryUrl)
    val resolved = base.resolve(value)
    val result = resolved.toASCIIString()
    requireSafeUrl(result, allowLocalHttp = true)
    return result
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal object NovelPluginVersions {
    fun compare(
        left: String,
        right: String,
    ): Int {
        val a = parse(left)
        val b = parse(right)
        val max = maxOf(a.core.size, b.core.size)
        repeat(max) { index ->
            val difference = (a.core.getOrNull(index) ?: 0).compareTo(b.core.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        if (a.preRelease == null && b.preRelease != null) return 1
        if (a.preRelease != null && b.preRelease == null) return -1
        return comparePreRelease(a.preRelease.orEmpty(), b.preRelease.orEmpty())
    }

    private fun parse(value: String): ParsedVersion {
        val clean = value.trim().removePrefix("v").substringBefore('+')
        val core = clean.substringBefore('-').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return ParsedVersion(core, clean.substringAfter('-', "").takeIf(String::isNotEmpty))
    }

    private fun comparePreRelease(
        left: String,
        right: String,
    ): Int {
        val a = left.split('.')
        val b = right.split('.')
        repeat(maxOf(a.size, b.size)) { index ->
            val l = a.getOrNull(index) ?: return -1
            val r = b.getOrNull(index) ?: return 1
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val comparison =
                when {
                    ln != null && rn != null -> ln.compareTo(rn)
                    ln != null -> -1
                    rn != null -> 1
                    else -> l.compareTo(r)
                }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class ParsedVersion(
        val core: List<Int>,
        val preRelease: String?,
    )
}
