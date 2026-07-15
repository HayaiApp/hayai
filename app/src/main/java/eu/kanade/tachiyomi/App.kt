package eu.kanade.tachiyomi

import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
// import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.appwidget.TachiyomiWidgetManager
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.library.LibraryPresenter
import eu.kanade.tachiyomi.ui.recents.RecentsPresenter
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import eu.kanade.tachiyomi.util.manga.MangaCoverMetadata
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notification
import eu.kanade.tachiyomi.util.system.setToDefault
import eu.kanade.tachiyomi.util.system.WebViewUtil
import java.security.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.conscrypt.Conscrypt
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.component.get
import org.koin.core.logger.Level
import yokai.core.CrashlyticsLogWriter
import yokai.core.RollingUniFileLogWriter
// EXH -->
import exh.di.exhModule
// EXH <--
// NOVEL -->
import hayai.novel.di.novelModule
// NOVEL <--
import yokai.core.di.appModule
import yokai.core.di.domainModule
import yokai.core.di.initExpensiveComponents
import yokai.core.di.preferenceModule
import yokai.core.migration.Migrator
import yokai.core.migration.migrations.migrations
import yokai.domain.base.BasePreferences
import yokai.domain.storage.StorageManager
import yokai.i18n.MR
import yokai.util.lang.getString

open class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory, Configuration.Provider, KoinComponent {

    val preferences: PreferencesHelper by inject()
    val basePreferences: BasePreferences by inject()
    val networkPreferences: NetworkPreferences by inject()
    private val storageManager: StorageManager by inject()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()

        // ── 1. Pre-DI platform shims ──────────────────────────────────────────────────────
        // TLS 1.3 backport on pre-Q.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
        // Per-process WebView data dir to avoid multi-process crashes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        // ── 2. DI ─────────────────────────────────────────────────────────────────────────
        // Koin modules registered first so injectLazy() in everything below resolves.
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@App)
            modules(
                preferenceModule(this@App),
                appModule(this@App),
                domainModule(),
                // EXH -->
                exhModule(),
                // EXH <--
                // NOVEL -->
                novelModule(),
                // NOVEL <--
            )
        }
        // Eagerly warm up heavy singletons on IO so the main thread isn't blocked when
        // MainActivity later calls into them. See AppModule.initExpensiveComponents.
        initExpensiveComponents(this)

        // EXH -->
        // Schedule EHentai gallery update worker if enabled
        // This will be wired when EHentaiUpdateWorkerConstants is available:
        // if (get<ExhPreferences>().autoUpdateFrequency().get() > 0) {
        //     EHentaiUpdateWorker.scheduleBackground(this)
        // }
        // EXH <--

        // ── 3. Process-lifetime observers and scope ──────────────────────────────────────
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        val scope = ProcessLifecycleOwner.get().lifecycleScope

        // Source icons otherwise perform PackageManager.loadIcon() Binder calls from the first
        // visible Library/Recents/Browse bind. Resolve the shared extension cache from IO once
        // per process; screen-level calls remain harmless idempotent fallbacks.
        scope.launchIO {
            GlobalContext.get().get<ExtensionManager>().preloadInstalledIcons()
        }

        // ── 4. One-shot side effects ──────────────────────────────────────────────────────
        // WebView init transiently rewrites the application context's package identity to
        // the WebView provider (com.android.chrome). Any system-service call from another
        // thread during that window hits checkCallerIsSameApp with a mismatched package
        // and throws SecurityException. Channels are created before WebView is touched.
        val channelsJob = scope.launchIO { setupNotificationChannels() }

        // Trigger NovelPluginManager init now so QuickJS metadata extraction happens at
        // app launch, not at first reader-open (where init awaits the source up to 5s).
        scope.launchIO {
            try {
                GlobalContext.get().get<hayai.novel.plugin.NovelPluginManager>()
            } catch (e: Throwable) {
                Logger.w(e) { "App: pre-warming NovelPluginManager failed" }
            }
        }

        // Pre-load WebView native libs so the first novel chapter doesn't pay the
        // WebView class-init cost (200–500 ms cold) on the UI thread.
        scope.launch {
            channelsJob.join()
            try {
                WebView(this@App).destroy()
            } catch (e: Throwable) {
                Logger.w(e) { "App: pre-warming WebView failed" }
            }
        }

        // Cover ratio/colour cache: heavy SharedPreferences read on first access; on IO so it
        // can't block the main thread. MangaCoverMetadata.load() merges into the existing
        // in-memory maps and gates savePrefs() until completion, so callers writing entries
        // before the load finishes don't have those writes clobbered or persisted as empty.
        scope.launchIO { MangaCoverMetadata.load() }

        // Glance widget: queries the host once on cold start; safe to do off-main.
        scope.launchIO { with(TachiyomiWidgetManager()) { this@App.init() } }

        // Default the hardware bitmap threshold from GL caps if the user hasn't set it.
        basePreferences.hardwareBitmapThreshold().let { preference ->
            if (!preference.isSet()) preference.set(GLUtil.DEVICE_TEXTURE_LIMIT)
        }

        // Read the last version code and start any pending migrations on IO. Migrator.await()
        // is consumed later by MainActivity to gate the "what's new" sheet, never the splash.
        initializeMigrator()

        // ── 5. Preference-driven flows ────────────────────────────────────────────────────
        // All registered with launchIn(scope); each is a one-line registration that doesn't
        // touch disk until the underlying SharedPreferences are loaded by their respective
        // backing keys.
        networkPreferences.verboseLogging().changes()
            .onEach { enabled ->
                // FlexibleAdapter.enableLogs(if (enabled) Level.VERBOSE else Level.SUPPRESS)
                Logger.setToDefault(buildLogWritersToAdd(storageManager.getLogsDirectory(), enabled))
            }
            .launchIn(scope)

        basePreferences.crashReport().changes()
            .onEach {
                try {
                    Firebase.crashlytics.setCrashlyticsCollectionEnabled(it)
                } catch (e: Exception) {
                    // Probably already enabled/disabled
                }
            }
            .launchIn(scope)

        preferences.nightMode().changes()
            .onEach { AppCompatDelegate.setDefaultNightMode(it) }
            .launchIn(scope)

        basePreferences.hardwareBitmapThreshold().changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(scope)

        // Incognito-mode notification: persistent foreground notification while enabled,
        // dismissed when toggled off. POST_NOTIFICATIONS gating handled inline.
        preferences.incognitoMode().changes()
            .onEach { enabled ->
                val notificationManager = NotificationManagerCompat.from(this)
                if (enabled) {
                    disableIncognitoReceiver.register()
                    val nContext = localeContext
                    val notification = nContext.notification(Notifications.CHANNEL_INCOGNITO_MODE) {
                        val incogText = nContext.getString(MR.strings.incognito_mode)
                        setContentTitle(incogText)
                        setContentText(nContext.getString(MR.strings.turn_off_, incogText))
                        setSmallIcon(R.drawable.ic_incognito_24dp)
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return@onEach
                    }
                    notificationManager.notify(Notifications.ID_INCOGNITO_MODE, notification)
                } else {
                    disableIncognitoReceiver.unregister()
                    notificationManager.cancel(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)
    }

    private fun initializeMigrator() {
        val preferenceStore = get<PreferenceStore>()

        val preference = preferenceStore.getInt(
            Preference.appStateKey("last_version_code"),
            0,
        )
        Logger.i { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                Logger.i { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!AuthenticatorUtil.isAuthenticating && preferences.lockAfter().get() >= 0) {
            SecureActivityDelegate.locked = true
        }
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.lowercase() in setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo") &&
                    trace.methodName.lowercase() in setOf("getall", "getpackagename", "<init>")
            }

            if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
        } catch (_: Exception) {
        }

        return super.getPackageName()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LibraryPresenter.onLowMemory()
        RecentsPresenter.onLowMemory()
        SourcePresenter.onLowMemory()
    }

    protected open fun setupNotificationChannels() {
        Notifications.createChannels(this)
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            preferences.incognitoMode().set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }

    // WorkManager lazy-init via Configuration.Provider. The manifest entry that disables
    // androidx.work.WorkManagerInitializer makes WorkManager wait for this configuration,
    // which is only requested after Application.onCreate completes — by which point Koin
    // is started and worker dependencies can resolve.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this@App).apply {
            val callFactoryLazy = lazy { get<NetworkHelper>().client }
            components {
                // Android 9 (P) and above has native support for these via ImageDecoder
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }

                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory (Tachiyomi's custom decoder for JXL/AVIF)
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                // Keyer
                add(MangaKeyer())
                add(MangaCoverKeyer())
            }

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build(),
            )

            // Off globally: in scroll-heavy grids the default 100ms per-cover alpha animator
            // saturates the main thread. The few surfaces that want it opt in per-request.
            crossfade(false)
            allowRgb565(this@App.getSystemService<ActivityManager>()!!.isLowRamDevice)
            // allowHardware(true)
            if (networkPreferences.verboseLogging().get()) {
                logger(DebugLogger())
            }

            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }
}

fun buildLogWritersToAdd(logPath: UniFile?): List<LogWriter> {
    val networkPreferences: NetworkPreferences = GlobalContext.get().get()
    return buildLogWritersToAdd(logPath, networkPreferences.verboseLogging().get())
}

fun buildLogWritersToAdd(
    logPath: UniFile?,
    isVerbose: Boolean,
) = buildList {
    if (!BuildConfig.DEBUG) add(CrashlyticsLogWriter())

 //   if (logPath != null && !BuildConfig.DEBUG) add(RollingUniFileLogWriter(logPath = logPath, isVerbose = isVerbose))
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"
