package eu.kanade.tachiyomi

import android.app.Application
import androidx.core.content.ContextCompat
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.adult.eh.session.AndroidEhCookieStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.source.EhSourceProvider
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.adult.eh.ui.EhDetailsPreviewLoader
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteSettingsRemote
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteSettingsUploader
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhUConfigHttpRemote
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.novel.integration.NovelJ2kIntegration
import dev.ahmedmohamed.hayai.novel.integration.NovelMigrationPolicy
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.TrustExtension
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AppModule(
    val app: Application,
) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory<PreferenceStore> {
            AndroidPreferenceStore(app)
        }

        addSingletonFactory { PreferencesHelper(app) }

        addSingletonFactory { TrackPreferences(get()) }

        addSingletonFactory { DatabaseHelper(app) }

        addSingletonFactory { ChapterCache(app) }

        addSingletonFactory { CoverCache(app) }

        addSingletonFactory { NetworkHelper(app) }

        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory { ExtensionManager(app) }
        addSingletonFactory { NovelPluginManager(app, get(), get()) }
        addSingletonFactory<EhCookieStore> { AndroidEhCookieStore() }
        addSingletonFactory { EhSessionStore(get(), get()) }
        addSingletonFactory { HayaiEhPersistenceStore(get()) }
        addSingletonFactory { EhPreferences(get()) }
        addSingletonFactory { EhHttpGateway(get<NetworkHelper>().client, get()) }
        addSingletonFactory<EhRemoteSettingsRemote> { EhUConfigHttpRemote(get<NetworkHelper>().client) }
        addSingletonFactory { EhRemoteSettingsUploader(get(), get(), get()) }
        addSingletonFactory { EhSourceProvider(get(), get(), get(), get()) }
        addSingletonFactory { EhDetailsPreviewLoader(get(), HayaiPreferences(get()), get()) }
        addSingletonFactory { SourceManager(app, get(), get(), get()) }
        addSingletonFactory { NovelJ2kIntegration(get(), get()) }
        addSingletonFactory { NovelMigrationPolicy(get(), get()) }

        addSingletonFactory { DownloadManager(app) }

        addSingletonFactory { CustomMangaManager(app) }

        addSingletonFactory { TrackManager(app) }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }

        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { ChapterFilter() }

        addSingletonFactory { MangaShortcutManager() }

        addSingletonFactory { TrustExtension(get()) }

        // Asynchronously init expensive components for a faster cold start

        ContextCompat.getMainExecutor(app).execute {
            get<PreferencesHelper>()

            get<NetworkHelper>()

            get<SourceManager>()

            get<DatabaseHelper>()

            get<DownloadManager>()

            get<CustomMangaManager>()
        }
    }
}
