package dev.ahmedmohamed.hayai.extension.install

import eu.kanade.tachiyomi.ui.extension.ExtensionIntallInfo
import kotlinx.coroutines.Job

/** An event belongs to the attempt that produced it, even after another attempt starts. */
data class ExtensionInstallEvent(val packageName: String, val info: ExtensionIntallInfo, val owner: Job?) {
    fun belongsTo(packageName: String, owner: Job): Boolean = this.packageName == packageName && this.owner === owner

    fun isVisible(currentOwner: Job?, latestOwner: Job?): Boolean =
        if (packageName.startsWith("Finished") || packageName.startsWith("Uninstalled")) {
            owner == null
        } else {
            owner === latestOwner && (
                (owner != null && owner.isActive && owner === currentOwner) ||
                    (info.first.isCompleted() && (currentOwner == null || owner === currentOwner))
                )
        }
}
