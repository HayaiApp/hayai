package eu.kanade.tachiyomi.extension.util

import android.annotation.SuppressLint
import android.content.Context
import dev.ahmedmohamed.hayai.extension.ApkLoadFailure
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.ahmedmohamed.hayai.novel.extension.NovelExtensionManifest
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Class that handles the loading of the extensions installed in the system.
 */
@SuppressLint("PackageManagerGetSignatures")
internal object ExtensionLoader {
    private val preferences: PreferencesHelper by injectLazy()
    private val trustExtension: TrustExtension by injectLazy()
    private val loadNsfwSource by lazy {
        preferences.showNsfwSources().get()
    }

    const val LIB_VERSION_MIN = 1.3
    const val LIB_VERSION_MAX = 1.6
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val NOVEL_EXTENSION_PACKAGE_PREFIX = "eu.kanade.tachiyomi.novelextension"

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS =
        PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"
    private var trustSignatures = mutableSetOf<String>()

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    fun installPrivateExtensionFile(
        context: Context,
        file: File,
    ): Boolean {
        val extension =
            context.packageManager
                .getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                Timber.e("Installed extension version is higher. Downgrading is not allowed.")
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                Timber.e("Extension to be installed is not signed.")
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                Timber.e("Installed extension signature is not matched.")
                return false
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        return try {
            file.copyTo(target, overwrite = true)
            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            Timber.e("Failed to copy extension file.")
            target.delete()
            false
        }
    }

    fun uninstallPrivateExtension(
        context: Context,
        pkgName: String,
    ) {
        File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()
    }

    private fun getExtensionsPackages(context: Context): List<ExtensionInfo> {
        val pkgManager = context.packageManager

        val installedPkgs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
            } else {
                pkgManager.getInstalledPackages(PACKAGE_FLAGS)
            }

        val sharedExtPkgs =
            installedPkgs
                .asSequence()
                .filter { isPackageAnExtension(it) }
                .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs =
            getPrivateExtensionDir(context)
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
                ?.mapNotNull {
                    it.setReadOnly()
                    val path = it.absolutePath
                    pkgManager
                        .getPackageArchiveInfo(path, PACKAGE_FLAGS)
                        ?.apply { applicationInfo?.fixBasePaths(path) }
                }?.filter { isPackageAnExtension(it) }
                ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
                ?: emptySequence()

        return (sharedExtPkgs + privateExtPkgs)
            // Remove duplicates. Shared takes priority than private by default
            .distinctBy { it.packageInfo.packageName }
            // Compare version number
            .mapNotNull { sharedPkg ->
                val privatePkg =
                    privateExtPkgs
                        .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }.toList()
    }

    /**
     * Return a list of all the installed extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadExtensions(context: Context): List<LoadResult> {
        val extPkgs = getExtensionsPackages(context)
        // Load each extension concurrently and wait for completion
        return runBlocking {
            val trustSigs =
                extPkgs.mapNotNull { extInfo ->
                    val pkgManager = context.packageManager
                    val pkgInfo = extInfo.packageInfo
                    val signatures = getSignatures(pkgInfo) ?: return@mapNotNull null
                    if (isExtensionInstalledByApp(context, pkgInfo.packageName) || isTrusted(pkgInfo, signatures)) {
                        return@mapNotNull signatures.lastOrNull()
                    }
                    return@mapNotNull null
                }
            trustSignatures.addAll(trustSigs)
            val deferred =
                extPkgs.map {
                    async { loadExtension(context, it) }
                }
            deferred.awaitAll()
        }
    }

    suspend fun loadExtensionAsync(context: Context): List<LoadResult> {
        val extPkgs = getExtensionsPackages(context)
        // Load each extension concurrently and wait for completion
        return withIOContext {
            val deferred =
                extPkgs.map {
                    async { loadExtension(context, it) }
                }
            deferred.awaitAll()
        }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    fun loadExtensionFromPkgName(
        context: Context,
        pkgName: String,
    ): LoadResult {
        val extensionPackage = getExtensionInfoFromPkgName(context, pkgName)
        if (extensionPackage == null) {
            Timber.e("Extension package is not found ($pkgName)")
            return LoadResult.Error(ApkLoadFailure(pkgName, reason = ApkLoadFailure.Reason.PackageMissing))
        }
        return loadExtension(context, extensionPackage)
    }

    fun getExtensionPackageInfoFromPkgName(
        context: Context,
        pkgName: String,
    ): PackageInfo? = getExtensionInfoFromPkgName(context, pkgName)?.packageInfo

    fun isExtensionPrivate(
        context: Context,
        pkgName: String,
    ): Boolean = getExtensionInfoFromPkgName(context, pkgName)?.isShared == false

    fun extensionInstallDate(
        context: Context,
        extension: Extension.Installed,
    ): Long =
        try {
            if (!extension.isShared) {
                val file = privateExtensionFile(context, extension.pkgName)
                val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                attr.creationTime().toMillis()
            } else {
                context.packageManager.getPackageInfo(extension.pkgName, 0).firstInstallTime
            }
        } catch (e: java.lang.Exception) {
            0
        }

    fun extensionUpdateDate(
        context: Context,
        extension: Extension.Installed,
    ): Long =
        try {
            if (!extension.isShared) {
                privateExtensionFile(context, extension.pkgName).lastModified()
            } else {
                context.packageManager.getPackageInfo(extension.pkgName, 0).lastUpdateTime
            }
        } catch (e: java.lang.Exception) {
            0
        }

    private fun privateExtensionFile(
        context: Context,
        pkgName: String,
    ): File = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")

    private fun getExtensionInfoFromPkgName(
        context: Context,
        pkgName: String,
    ): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg =
            if (privateExtensionFile.isFile) {
                privateExtensionFile.setReadOnly()
                context.packageManager
                    .getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                    ?.takeIf { isPackageAnExtension(it) }
                    ?.let {
                        it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath)
                        ExtensionInfo(
                            packageInfo = it,
                            isShared = false,
                        )
                    }
            } else {
                null
            }

        val sharedPkg =
            try {
                context.packageManager
                    .getPackageInfo(pkgName, PACKAGE_FLAGS)
                    .takeIf { isPackageAnExtension(it) }
                    ?.let {
                        ExtensionInfo(
                            packageInfo = it,
                            isShared = true,
                        )
                    }
            } catch (error: PackageManager.NameNotFoundException) {
                null
            }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    fun isExtensionInstalledByApp(
        context: Context,
        pkgName: String,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(pkgName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(pkgName)
            } == BuildConfig.APPLICATION_ID
        } catch (e: java.lang.Exception) {
            false
        }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
    ): LoadResult {
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName
        val metadata = appInfo.metaData

        val requiredFeatures = pkgInfo.reqFeatures.orEmpty().mapNotNull { it.name }.toSet()
        val manifest = NovelExtensionManifest.resolve(requiredFeatures, metadata?.keySet().orEmpty())
        if (manifest == null) {
            Timber.w("Missing supported extension feature for $pkgName")
            return LoadResult.Error(ApkLoadFailure(pkgName, reason = ApkLoadFailure.Reason.UnsupportedManifest))
        }

        val extName =
            if (manifest.isNovel) {
                NovelExtensionManifest
                    .displayName(
                        applicationLabel = pkgManager.getApplicationLabel(appInfo).toString(),
                        metadataName = metadata?.getString(NovelExtensionManifest.DISPLAY_NAME_KEY),
                    ).ifBlank { pkgName }
            } else {
                // Keep standard APK identity exactly on J2K's path.
                pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
            }
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        fun failure(reason: ApkLoadFailure.Reason) =
            LoadResult.Error(ApkLoadFailure(pkgName, extName, versionName, reason))

        if (versionName.isNullOrEmpty()) {
            Timber.w("Missing versionName for extension $extName")
            return failure(ApkLoadFailure.Reason.VersionMissing)
        }

        // Validate lib version
        val libVersion =
            if (manifest.isNovel) {
                val manifestLibVersion =
                    metadata
                        ?.takeIf { it.containsKey(NovelExtensionManifest.EXTENSION_LIB_KEY) }
                        ?.getFloat(NovelExtensionManifest.EXTENSION_LIB_KEY)
                NovelExtensionManifest.libraryVersion(versionName, manifestLibVersion)
            } else {
                // This is J2K's authoritative decoding rule for manga APK extensions.
                versionName.substringBeforeLast('.').toDoubleOrNull()
            }
        if (libVersion == null || libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
            Timber.w(
                "Lib version is $libVersion, while only versions $LIB_VERSION_MIN to $LIB_VERSION_MAX are allowed",
            )
            return failure(ApkLoadFailure.Reason.UnsupportedLibrary)
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            Timber.w("Package $pkgName isn't signed")
            return failure(ApkLoadFailure.Reason.Unsigned)
        } else if (isExtensionInstalledByApp(context, pkgName)) {
            if (!trustSignatures.contains(signatures.last())) {
                trustSignatures.add(signatures.last())
            }
        } else if (!isTrusted(pkgInfo, signatures) && signatures.any { !trustSignatures.contains(it) }) {
            val extension =
                Extension.Untrusted(
                    extName,
                    pkgName,
                    versionName,
                    versionCode,
                    libVersion,
                    signatures.last(),
                    isNovel = manifest.isNovel || pkgName.startsWith(NOVEL_EXTENSION_PACKAGE_PREFIX),
                )
            Timber.w("Extension $pkgName isn't trusted")
            return LoadResult.Untrusted(extension)
        }

        val isNsfw =
            if (manifest.isNovel) {
                (metadata?.getInt(NovelExtensionManifest.CONTENT_WARNING_KEY) ?: 0) > 0 ||
                    (metadata?.getInt(manifest.nsfwKey) ?: 0) == 1
            } else {
                metadata?.getInt(METADATA_NSFW) == 1
            }
        if (!loadNsfwSource && isNsfw) {
            Timber.w("NSFW extension $pkgName not allowed")
            return failure(ApkLoadFailure.Reason.AdultSourcesDisabled)
        }

        val classLoader =
            try {
                ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
            } catch (error: Exception) {
                Timber.e(error, "Extension class loader error: $extName ($pkgName)")
                return failure(ApkLoadFailure.Reason.ClassLoader)
            }

        val classMetadataKey = if (manifest.isNovel) manifest.classKey else METADATA_SOURCE_CLASS
        val declaredClasses = metadata?.getString(classMetadataKey)
        if (declaredClasses.isNullOrBlank()) {
            Timber.w("Missing $classMetadataKey for extension $extName ($pkgName)")
            return failure(ApkLoadFailure.Reason.ClassMetadata)
        }

        val sources =
            declaredClasses
                .split(";")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .flatMap { declaredClass ->
                    val candidates =
                        if (manifest.isNovel) {
                            NovelExtensionManifest.classCandidates(declaredClass, pkgInfo.packageName)
                        } else {
                            listOf(if (declaredClass.startsWith('.')) pkgInfo.packageName + declaredClass else declaredClass)
                        }
                    var lastClassError: ClassNotFoundException? = null
                    for (className in candidates) {
                        try {
                            val obj = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()
                            return@flatMap when (obj) {
                                is Source -> listOf(obj)
                                is SourceFactory -> obj.createSources()
                                else -> throw Exception("Unknown source class type! ${obj.javaClass}")
                            }
                        } catch (error: ClassNotFoundException) {
                            lastClassError = error
                        } catch (error: Throwable) {
                            Timber.e(error, "Extension load error: $extName ($className)")
                            return failure(ApkLoadFailure.Reason.SourceConstruction)
                        }
                    }

                    Timber.e(lastClassError, "Extension class not found: $extName ($declaredClass)")
                    return failure(ApkLoadFailure.Reason.SourceConstruction)
                }

        if (manifest.isNovel && sources.isEmpty()) {
            Timber.w("Extension $extName ($pkgName) did not declare any source classes")
            return failure(ApkLoadFailure.Reason.EmptySources)
        }
        if (manifest.isNovel && sources.none { it.isNovelSource() }) {
            Timber.w("Novel extension $extName ($pkgName) did not expose a novel source")
            return failure(ApkLoadFailure.Reason.NovelContract)
        }

        val langs =
            sources
                .filterIsInstance<CatalogueSource>()
                .map { it.lang }
                .toSet()
        val lang =
            when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            }

        val extension =
            Extension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                libVersion = libVersion,
                lang = lang,
                isNsfw = isNsfw,
                isNovel = manifest.isNovel,
                sources = sources,
                pkgFactory = metadata?.getString(if (manifest.isNovel) manifest.factoryKey else METADATA_SOURCE_FACTORY),
                icon = appInfo.loadIcon(pkgManager),
                isShared = extensionInfo.isShared,
            )
        return LoadResult.Success(extension)
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(
        shared: ExtensionInfo?,
        private: ExtensionInfo?,
    ): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    fun isPackageNameAnExtension(
        packageManager: PackageManager,
        pkgName: String,
    ): Boolean = isPackageAnExtension(packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS))

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean =
        NovelExtensionManifest.isSupported(pkgInfo.reqFeatures.orEmpty().map { it.name })

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()

    private fun isTrusted(
        pkgInfo: PackageInfo,
        signatures: List<String>,
    ): Boolean = trustExtension.isTrusted(pkgInfo, signatures.last())

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}
