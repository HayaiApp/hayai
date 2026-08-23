package dev.ahmedmohamed.hayai.extension.managed

import android.graphics.drawable.Drawable
import dev.ahmedmohamed.hayai.extension.ApkLoadFailure
import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import eu.kanade.tachiyomi.extension.model.Extension

sealed interface ManagedExtensionKey {
    val stableValue: String

    data class Apk(val packageName: String) : ManagedExtensionKey {
        override val stableValue: String = "apk:$packageName"
    }

    data class Js(val pluginId: String) : ManagedExtensionKey {
        override val stableValue: String = "js:$pluginId"
    }
}

enum class ManagedExtensionBackend { Apk, JavaScript }

enum class ManagedInstallation { Available, Installed, Untrusted }

enum class ManagedUpdate { None, Available }

sealed interface ManagedHealth {
    data object Ready : ManagedHealth

    data class LoadFailed(val diagnostic: String) : ManagedHealth
}

data class ManagedExtensionEntry(
    val key: ManagedExtensionKey,
    val backend: ManagedExtensionBackend,
    val name: String,
    val language: String?,
    val installedVersion: String?,
    val availableVersion: String?,
    val installation: ManagedInstallation,
    val update: ManagedUpdate,
    val health: ManagedHealth,
    val contentKinds: Set<ContentKind>,
    val isNsfw: Boolean,
    val installedIcon: Drawable? = null,
    val iconUrl: String? = null,
    val websiteUrl: String? = null,
) {
    val hasUpdate: Boolean get() = update == ManagedUpdate.Available
}

data class ApkExtensionSnapshot(
    val installed: List<Extension.Installed>,
    val untrusted: List<Extension.Untrusted>,
    val available: List<Extension.Available>,
    val failures: List<ApkLoadFailure>,
)

data class ManagedExtensionFilter(
    val enabledLanguages: Set<String>,
    val showNsfwSources: Boolean,
)

data class ManagedExtensionNotice(
    val identity: String,
    val diagnostic: String,
)

data class ManagedExtensionState(
    val updates: List<ManagedExtensionEntry>,
    val installed: List<ManagedExtensionEntry>,
    val available: List<ManagedExtensionEntry>,
    val notices: List<ManagedExtensionNotice>,
    val refreshing: Boolean,
) {
    val entries: List<ManagedExtensionEntry> get() = updates + installed + available
}
