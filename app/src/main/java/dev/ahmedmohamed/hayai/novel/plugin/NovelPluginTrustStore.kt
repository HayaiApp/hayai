package dev.ahmedmohamed.hayai.novel.plugin

import android.content.Context
import android.util.Base64
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class NovelRepositoryTrust(
    val repositoryUrl: String,
    val signingKey: String?,
    val fingerprint: String,
    val trustedAt: Long,
)

internal class NovelPluginTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("hayai_novel_repository_trust", Context.MODE_PRIVATE)

    fun trustUnsigned(repositoryUrl: String) {
        requireSafeUrl(repositoryUrl, allowLocalHttp = true)
        novelRequire(preferences.edit().putString(key(repositoryUrl), "unsigned:${System.currentTimeMillis()}").commit(), NovelFailure.Code.PluginTrustPersist)
    }

    fun observeSigningKey(repositoryUrl: String, publicKey: String) {
        val bytes = decode(publicKey)
        novelRequire(bytes.size in 32..128, NovelFailure.Code.PluginPublicKey)
        val fingerprint = sha256Hex(bytes)
        val stored = preferences.getString(key(repositoryUrl), null)
        if (stored != null && stored.startsWith("signed:")) {
            novelRequire(stored.substringAfter("signed:").substringBefore(':') == fingerprint, NovelFailure.Code.PluginSigningKeyChanged)
        }
        novelRequire(preferences.edit().putString(key(repositoryUrl), "signed:$fingerprint:${System.currentTimeMillis()}").commit(), NovelFailure.Code.PluginTrustPersist)
    }

    fun revoke(repositoryUrl: String) {
        novelRequire(preferences.edit().remove(key(repositoryUrl)).commit(), NovelFailure.Code.PluginTrustPersist)
    }

    fun trust(repositoryUrl: String, publicKey: String?): NovelRepositoryTrust? {
        val value = preferences.getString(key(repositoryUrl), null) ?: return null
        val fingerprint = if (value.startsWith("signed:")) {
            value.substringAfter("signed:").substringBefore(':')
        } else {
            "unsigned"
        }
        val timestamp = value.substringAfterLast(':').toLongOrNull() ?: 0L
        return NovelRepositoryTrust(repositoryUrl, publicKey, fingerprint, timestamp)
    }

    fun verify(repositoryUrl: String, descriptor: NovelPluginDescriptor, code: ByteArray) {
        val value = preferences.getString(key(repositoryUrl), null) ?: novelFailure(NovelFailure.Code.PluginRepositoryUntrusted)
        if (descriptor.signingKey == null) {
            novelRequire(value.startsWith("unsigned:"), NovelFailure.Code.PluginUnsignedFromSigned)
            return
        }
        novelRequire(value.startsWith("signed:"), NovelFailure.Code.PluginSignatureUntrusted)
        val keyBytes = decode(descriptor.signingKey)
        novelRequire(sha256Hex(keyBytes) == value.substringAfter("signed:").substringBefore(':'), NovelFailure.Code.PluginSigningKeyChanged)
        val key = runCatching {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(normalizePublicKey(keyBytes)))
        }.getOrElse { throw NovelFailure(NovelFailure.Code.PluginPublicKeyUnsupported, cause = it) }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(code)
        novelRequire(verifier.verify(decode(requireNotNull(descriptor.signature))), NovelFailure.Code.PluginSignatureFailed)
    }

    private fun normalizePublicKey(value: ByteArray): ByteArray =
        if (value.size == 32) ED25519_X509_PREFIX + value else value

    private fun decode(value: String): ByteArray = runCatching {
        Base64.decode(value, Base64.DEFAULT)
    }.getOrElse { throw NovelFailure(NovelFailure.Code.PluginBase64, cause = it) }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return "repo_${digest.joinToString("") { "%02x".format(it) }}"
    }

    private companion object {
        val ED25519_X509_PREFIX = byteArrayOf(
            0x30,
            0x2a,
            0x30,
            0x05,
            0x06,
            0x03,
            0x2b,
            0x65,
            0x70,
            0x03,
            0x21,
            0x00,
        )
    }
}
