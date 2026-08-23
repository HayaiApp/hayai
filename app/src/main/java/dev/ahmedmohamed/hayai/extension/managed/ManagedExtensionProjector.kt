package dev.ahmedmohamed.hayai.extension.managed

import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginCatalog
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginVersions
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.isNovelSource

object ManagedExtensionProjector {
    fun project(
        apk: ApkExtensionSnapshot,
        js: NovelPluginCatalog,
        filter: ManagedExtensionFilter,
    ): ManagedExtensionState {
        val apkAvailable = apk.available.associateBy(Extension.Available::pkgName)
        val apkFailures = apk.failures.associateBy { it.packageName }
        val jsAvailable = js.available.associateBy { it.id }
        val jsInstalled = js.installed.associateBy { it.descriptor.id }

        val entries = buildList {
            apk.installed.forEach { installed ->
                val available = apkAvailable[installed.pkgName]
                val failure = apkFailures[installed.pkgName]
                add(
                    ManagedExtensionEntry(
                        key = ManagedExtensionKey.Apk(installed.pkgName),
                        backend = ManagedExtensionBackend.Apk,
                        name = installed.name,
                        language = installed.lang,
                        installedVersion = installed.versionName,
                        availableVersion = available?.versionName,
                        installation = ManagedInstallation.Installed,
                        update = if (installed.hasUpdate) ManagedUpdate.Available else ManagedUpdate.None,
                        health = failure?.let { ManagedHealth.LoadFailed(it.reason.name) } ?: ManagedHealth.Ready,
                        contentKinds = installed.contentKinds(),
                        isNsfw = installed.isNsfw,
                        installedIcon = installed.icon,
                        websiteUrl = available?.sources?.firstOrNull { it.baseUrl.isNotBlank() }?.baseUrl,
                    ),
                )
            }
            apk.untrusted.forEach { extension ->
                add(
                    ManagedExtensionEntry(
                        key = ManagedExtensionKey.Apk(extension.pkgName),
                        backend = ManagedExtensionBackend.Apk,
                        name = extension.name,
                        language = extension.lang,
                        installedVersion = extension.versionName,
                        availableVersion = apkAvailable[extension.pkgName]?.versionName,
                        installation = ManagedInstallation.Untrusted,
                        update = ManagedUpdate.None,
                        health = ManagedHealth.Ready,
                        contentKinds = setOf(if (extension.isNovel) ContentKind.Novel else ContentKind.Manga),
                        isNsfw = extension.isNsfw,
                    ),
                )
            }
            apk.failures
                .filterNot { failure ->
                    apk.installed.any { it.pkgName == failure.packageName } ||
                        apk.untrusted.any { it.pkgName == failure.packageName }
                }.forEach { failure ->
                    val available = apkAvailable[failure.packageName]
                    add(
                        ManagedExtensionEntry(
                            key = ManagedExtensionKey.Apk(failure.packageName),
                            backend = ManagedExtensionBackend.Apk,
                            name = failure.displayName,
                            language = available?.lang,
                            installedVersion = failure.versionName,
                            availableVersion = available?.versionName,
                            installation = ManagedInstallation.Installed,
                            update = ManagedUpdate.None,
                            health = ManagedHealth.LoadFailed(failure.reason.name),
                            contentKinds = setOf(if (available?.isNovel == true) ContentKind.Novel else ContentKind.Manga),
                            isNsfw = available?.isNsfw == true,
                            iconUrl = available?.iconUrl,
                        ),
                    )
                }
            apk.available
                .filterNot { extension ->
                    apk.installed.any { it.pkgName == extension.pkgName } ||
                        apk.untrusted.any { it.pkgName == extension.pkgName } ||
                        apk.failures.any { it.packageName == extension.pkgName }
                }.filter { it.lang in filter.enabledLanguages && (filter.showNsfwSources || !it.isNsfw) }
                .forEach { extension ->
                    add(
                        ManagedExtensionEntry(
                            key = ManagedExtensionKey.Apk(extension.pkgName),
                            backend = ManagedExtensionBackend.Apk,
                            name = extension.name,
                            language = extension.lang,
                            installedVersion = null,
                            availableVersion = extension.versionName,
                            installation = ManagedInstallation.Available,
                            update = ManagedUpdate.None,
                            health = ManagedHealth.Ready,
                            contentKinds = setOf(if (extension.isNovel) ContentKind.Novel else ContentKind.Manga),
                            isNsfw = extension.isNsfw,
                            iconUrl = extension.iconUrl,
                            websiteUrl = extension.sources.firstOrNull { it.baseUrl.isNotBlank() }?.baseUrl,
                        ),
                    )
                }

            (jsAvailable.keys + jsInstalled.keys).forEach { id ->
                val installed = jsInstalled[id]
                val available = jsAvailable[id]
                val descriptor = available ?: installed?.descriptor ?: return@forEach
                if (installed == null && descriptor.normalizedLanguage() !in filter.enabledLanguages) return@forEach
                val origin = js.availableOrigins[id] ?: installed?.repositoryUrl
                val hasUpdate = installed != null && available != null && NovelPluginVersions.compare(available.version, installed.descriptor.version) > 0
                add(
                    ManagedExtensionEntry(
                        key = ManagedExtensionKey.Js(id),
                        backend = ManagedExtensionBackend.JavaScript,
                        name = descriptor.name,
                        language = descriptor.normalizedLanguage(),
                        installedVersion = installed?.descriptor?.version,
                        availableVersion = available?.version,
                        installation = if (installed == null) ManagedInstallation.Available else ManagedInstallation.Installed,
                        update = if (hasUpdate) ManagedUpdate.Available else ManagedUpdate.None,
                        health = js.runtimeErrors[id]?.let { ManagedHealth.LoadFailed(it.message ?: it::class.java.simpleName) } ?: ManagedHealth.Ready,
                        contentKinds = setOf(ContentKind.Novel),
                        isNsfw = false,
                        iconUrl = origin?.let { runCatching { descriptor.resolvedIconUrl(it) }.getOrNull() },
                        websiteUrl = descriptor.site.takeIf(String::isNotBlank),
                    ),
                )
            }
        }.distinctBy { it.key }

        val comparator = compareBy<ManagedExtensionEntry>({ it.name.lowercase() }, { it.key.stableValue })
        val notices = buildList {
            js.repositoryErrors.forEach { (url, error) -> add(ManagedExtensionNotice("js-repository:$url", error.message ?: error::class.java.simpleName)) }
        }
        return ManagedExtensionState(
            updates = entries.filter { it.hasUpdate }.sortedWith(comparator),
            installed = entries.filter { !it.hasUpdate && it.installation != ManagedInstallation.Available }.sortedWith(comparator),
            available = entries.filter { it.installation == ManagedInstallation.Available }.sortedWith(comparator),
            notices = notices,
            refreshing = js.refreshing,
        )
    }

    private fun Extension.Installed.contentKinds(): Set<ContentKind> =
        sources.map { if (it.isNovelSource()) ContentKind.Novel else ContentKind.Manga }
            .toSet()
            .ifEmpty { setOf(if (isNovel) ContentKind.Novel else ContentKind.Manga) }
}
