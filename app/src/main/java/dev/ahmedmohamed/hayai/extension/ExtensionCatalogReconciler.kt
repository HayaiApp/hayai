package dev.ahmedmohamed.hayai.extension

import eu.kanade.tachiyomi.extension.model.Extension
import dev.ahmedmohamed.hayai.extension.managed.ApkExtensionSnapshot

/**
 * Gives every APK-extension consumer the same package and version decision.
 *
 * Repositories may publish the same package. Selecting different copies for the
 * list, update check, and installer leaves an update permanently pending.
 */
object ExtensionCatalogReconciler {
    fun recordUntrusted(snapshot: ApkExtensionSnapshot, extension: Extension.Untrusted): ApkExtensionSnapshot = snapshot.copy(
        installed = snapshot.installed.filterNot { it.pkgName == extension.pkgName },
        untrusted = snapshot.untrusted.filterNot { it.pkgName == extension.pkgName } + extension,
        failures = snapshot.failures.filterNot { it.packageName == extension.pkgName },
    )

    fun newestCopies(extensions: List<Extension.Available>): List<Extension.Available> =
        extensions
            .groupBy(Extension.Available::pkgName)
            .values
            .map { copies ->
                copies.maxWith(
                    compareBy<Extension.Available>(Extension.Available::versionCode)
                        .thenBy(Extension.Available::libVersion)
                        .thenBy(Extension.Available::repoUrl),
                )
            }

    fun hasUpdate(
        installed: Extension.Installed,
        available: Extension.Available,
    ): Boolean = available.versionCode > installed.versionCode || available.libVersion > installed.libVersion
}
