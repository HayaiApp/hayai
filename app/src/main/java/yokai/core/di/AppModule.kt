package yokai.core.di

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import co.touchlab.kermit.Logger
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.storage.AndroidStorageFolderProvider
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import org.koin.dsl.module
import yokai.data.AndroidDatabaseHandler
import yokai.data.Database
import yokai.data.DatabaseHandler
import yokai.domain.storage.StorageManager

fun appModule(app: Application) = module {
    single { app }

    single<SqlDriver> {
        AndroidSqliteDriver(
            schema = Database.Schema,
            context = app,
            name = "tachiyomi.db",
            // factory = if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //     // Support database inspector in Android Studio
            //     FrameworkSQLiteOpenHelperFactory()
            // } else {
            //     RequerySQLiteOpenHelperFactory()
            // },
            factory = RequerySQLiteOpenHelperFactory(),
            callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    setPragma(db, "foreign_keys = ON")
                    setPragma(db, "journal_mode = WAL")
                    setPragma(db, "synchronous = NORMAL")
                }

                private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                    db.query("PRAGMA $pragma").use { cursor ->
                        cursor.moveToFirst()
                    }
                }

                // Not sure if this is still needed, but just in case
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    Logger.d { "Creating new database..." }
                    super.onCreate(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    if (oldVersion < newVersion) {
                        Logger.d { "Upgrading database from $oldVersion to $newVersion" }
                        super.onUpgrade(db, oldVersion, newVersion)
                    }
                }
            },
        )
    }

    single {
        Database(
            driver = get(),
        )
    }
    single<DatabaseHandler> { AndroidDatabaseHandler(get(), get()) }

    single { ChapterCache(app) }

    single { CoverCache(app) }

    single {
        NetworkHelper(
            app,
            get(),
        ) { builder ->
            if (BuildConfig.DEBUG) {
                builder.addInterceptor(
                    ChuckerInterceptor.Builder(app)
                        .collector(ChuckerCollector(app))
                        .maxContentLength(250000L)
                        .redactHeaders(emptySet())
                        .alwaysReadResponseBody(false)
                        .build(),
                )
            }
        }
    }

    single { JavaScriptEngine(app) }

    single { SourceManager(app, get()) }
    single { ExtensionManager(app) }

    single { DownloadProvider(app) }
    single { DownloadManager(app) }
    single { DownloadCache(app) }

    single { CustomMangaManager(app) }

    single { TrackManager(app) }

    single { TranslationCache(app, get()) }
    single { TranslationEngineManager(get()) }
    single { TranslationService(app, get(), get(), get(), get(), get(), get()) }

    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    single {
        XML {
            defaultPolicy {
                ignoreUnknownChildren()
            }
            autoPolymorphic = true
            xmlDeclMode = XmlDeclMode.Charset
            indent = 2
            xmlVersion = XmlVersion.XML10
        }
    }
    single<ProtoBuf> {
        ProtoBuf
    }

    single { ChapterFilter() }

    single { MangaShortcutManager() }

    single { AndroidStorageFolderProvider(app) }
    single { StorageManager(app, get()) }
}

// REF: https://github.com/jobobby04/TachiyomiSY/blob/26cfb4811fef4059fb7e8e03361c141932fec6b5/app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt#L177C1-L192C2
fun initExpensiveComponents(app: Application) {
    // Asynchronously init expensive components on IO so cold start does not block the main thread.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val koin = org.koin.core.context.GlobalContext.get()
        koin.get<NetworkHelper>()
        koin.get<SourceManager>()
        koin.get<Database>()
        koin.get<DownloadManager>()
        koin.get<CustomMangaManager>()
    }
}
