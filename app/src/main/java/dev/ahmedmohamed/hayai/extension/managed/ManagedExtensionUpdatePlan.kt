package dev.ahmedmohamed.hayai.extension.managed

import dev.ahmedmohamed.hayai.novel.integration.ContentKind

data class ManagedExtensionUpdatePlan(val apkPackages: List<String>, val pluginIds: List<String>) {
    companion object {
        fun create(entries: List<ManagedExtensionEntry>, kind: ContentKind, active: Set<ManagedExtensionKey>): ManagedExtensionUpdatePlan {
            val updates = entries.filter {
                kind in it.contentKinds && it.installation == ManagedInstallation.Installed && it.hasUpdate && it.key !in active
            }.distinctBy(ManagedExtensionEntry::key)
            return ManagedExtensionUpdatePlan(
                updates.mapNotNull { (it.key as? ManagedExtensionKey.Apk)?.packageName },
                updates.mapNotNull { (it.key as? ManagedExtensionKey.Js)?.pluginId },
            )
        }
    }
}
