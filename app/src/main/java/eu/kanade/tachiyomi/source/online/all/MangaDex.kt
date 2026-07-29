package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.FollowsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.LoginSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.RandomMangaSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.handlers.ApiMangaParser
import exh.md.handlers.AzukiHandler
import exh.md.handlers.BilibiliHandler
import exh.md.handlers.ComikeyHandler
import exh.md.handlers.FollowsHandler
import exh.md.handlers.MangaHandler
import exh.md.handlers.MangaHotHandler
import exh.md.handlers.MangaPlusHandler
import exh.md.handlers.NamicomiHandler
import exh.md.handlers.PageHandler
import exh.md.handlers.SimilarHandler
import exh.md.network.MangaDexAuthInterceptor
import exh.md.network.MangaDexLoginHelper
import exh.md.service.MangaDexAuthService
import exh.md.service.MangaDexService
import exh.md.service.SimilarService
import exh.md.utils.FollowStatus
import exh.md.utils.MdApi
import exh.md.utils.MdLang
import exh.md.utils.MdUtil
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.source.DelegatedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Response
import rx.Observable
import yokai.util.koin.injectLazy
import yokai.util.lang.runAsObservable
import kotlin.reflect.KClass

@Suppress("OverridingDeprecatedMember")
class MangaDex(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<MangaDexSearchMetadata, Triple<MangaDto, List<String>, StatisticsMangaDto>>,
    UrlImportableSource,
    FollowsSource,
    LoginSource,
    RandomMangaSource,
    NamespaceSource {
    override val lang: String = delegate.lang

    // DelegatedHttpSource abstract members
    override val domainName: String = "mangadex"
    override fun canOpenUrl(uri: Uri): Boolean {
        return uri.pathSegments?.lastOrNull() != "comments"
    }
    override fun chapterUrl(uri: Uri): String? {
        val chapterNumber = uri.pathSegments.getOrNull(1) ?: return null
        return "/chapter/$chapterNumber"
    }
    override suspend fun fetchMangaFromChapterUrl(uri: Uri): Triple<SChapter, SManga, List<SChapter>>? = null

    private val mdLang by lazy {
        MdLang.fromExt(lang) ?: MdLang.ENGLISH
    }

    override val matchingHosts: List<String> = listOf("mangadex.org", "www.mangadex.org")

    val preferenceStore: PreferenceStore by injectLazy()

    private val sourcePreferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    private val mangaDexAuthInterceptor = MangaDexAuthInterceptor(preferenceStore)

    private val loginHelper = MangaDexLoginHelper(network.client, preferenceStore, mangaDexAuthInterceptor)

    override val baseHttpClient: OkHttpClient? = delegate.client.newBuilder()
        .addInterceptor(mangaDexAuthInterceptor)
        .build()

    private fun dataSaver() = sourcePreferences.getBoolean(getDataSaverPreferenceKey(mdLang.lang), false)
    private fun usePort443Only() = sourcePreferences.getBoolean(getStandardHttpsPreferenceKey(mdLang.lang), false)
    private fun blockedGroups() = sourcePreferences.getString(getBlockedGroupsPrefKey(mdLang.lang), "").orEmpty()
    private fun blockedUploaders() = sourcePreferences.getString(getBlockedUploaderPrefKey(mdLang.lang), "").orEmpty()
    private fun coverQuality() = sourcePreferences.getString(getCoverQualityPrefKey(mdLang.lang), "").orEmpty()
    private fun tryUsingFirstVolumeCover() = sourcePreferences.getBoolean(getTryUsingFirstVolumeCoverKey(mdLang.lang), false)
    private fun altTitlesInDesc() = sourcePreferences.getBoolean(getAltTitlesInDescKey(mdLang.lang), false)
    private fun finalChapterInDesc() = sourcePreferences.getBoolean(getFinalChapterInDescPrefKey(mdLang.lang), false)
    private fun preferExtensionLangTitle() = sourcePreferences.getBoolean(getPreferExtensionLangTitlePrefKey(mdLang.extLang), true)

    private val mangadexService by lazy {
        MangaDexService(client)
    }
    private val mangadexAuthService by lazy {
        MangaDexAuthService(baseHttpClient ?: client, headers)
    }
    private val similarService by lazy {
        SimilarService(client)
    }
    private val apiMangaParser by lazy {
        ApiMangaParser(mdLang.lang)
    }
    private val followsHandler by lazy {
        FollowsHandler(mdLang.lang, mangadexAuthService)
    }
    private val mangaHandler by lazy {
        MangaHandler(mdLang.lang, mangadexService, apiMangaParser)
    }
    private val similarHandler by lazy {
        SimilarHandler(mdLang.lang, mangadexService, similarService)
    }
    private val mangaPlusHandler by lazy {
        MangaPlusHandler(network.client)
    }
    private val comikeyHandler by lazy {
        ComikeyHandler(network.client, network.defaultUserAgent)
    }
    private val bilibiliHandler by lazy {
        BilibiliHandler(network.client)
    }
    private val azukHandler by lazy {
        AzukiHandler(network.client, network.defaultUserAgent)
    }
    private val mangaHotHandler by lazy {
        MangaHotHandler(network.client, network.defaultUserAgent)
    }
    private val namicomiHandler by lazy {
        NamicomiHandler(network.client, network.defaultUserAgent)
    }
    private val pageHandler by lazy {
        PageHandler(
            headers,
            mangadexService,
            mangaPlusHandler,
            comikeyHandler,
            bilibiliHandler,
            azukHandler,
            mangaHotHandler,
            namicomiHandler,
        )
    }

    // UrlImportableSource methods
    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        val lcFirstPathSegment = uri.pathSegments.firstOrNull()?.lowercase() ?: return null

        return if (lcFirstPathSegment == "title" || lcFirstPathSegment == "manga") {
            MdUtil.buildMangaUrl(uri.pathSegments[1])
        } else {
            null
        }
    }

    override fun mapUrlToChapterUrl(uri: Uri): String? {
        if (!uri.pathSegments.firstOrNull().equals("chapter", true)) return null
        val id = uri.pathSegments.getOrNull(1) ?: return null
        return MdApi.chapter + '/' + id
    }

    override suspend fun mapChapterUrlToMangaUrl(uri: Uri): String? {
        val id = uri.pathSegments.getOrNull(1) ?: return null
        return mangaHandler.getMangaFromChapterId(id)?.let { MdUtil.buildMangaUrl(it) }
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        @Suppress("DEPRECATION")
        return super<DelegatedHttpSource>.fetchLatestUpdates(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return super<DelegatedHttpSource>.getLatestUpdates(page)
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return mangaHandler.fetchMangaDetailsObservable(
            manga,
            id,
            coverQuality(),
            tryUsingFirstVolumeCover(),
            altTitlesInDesc(),
            finalChapterInDesc(),
            preferExtensionLangTitle(),
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return mangaHandler.getMangaDetails(
            manga,
            id,
            coverQuality(),
            tryUsingFirstVolumeCover(),
            altTitlesInDesc(),
            finalChapterInDesc(),
            preferExtensionLangTitle(),
        )
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return mangaHandler.fetchChapterListObservable(manga, blockedGroups(), blockedUploaders())
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        return mangaHandler.getChapterList(manga, blockedGroups(), blockedUploaders())
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return runAsObservable { pageHandler.fetchPageList(chapter, usePort443Only(), dataSaver(), delegate) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return pageHandler.fetchPageList(chapter, usePort443Only(), dataSaver(), delegate)
    }

    override suspend fun getImage(page: Page, existingSize: Long): Response {
        val call = pageHandler.getImageCall(page)
        return call?.awaitSuccess() ?: super.getImage(page, existingSize)
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
    override fun fetchImageUrl(page: Page): Observable<String> {
        return pageHandler.fetchImageUrl(page) {
            @Suppress("DEPRECATION")
            super.fetchImageUrl(it)
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        return pageHandler.getImageUrl(page) {
            super.getImageUrl(page)
        }
    }

    // MetadataSource methods
    override val metaClass: KClass<MangaDexSearchMetadata> = MangaDexSearchMetadata::class

    override fun newMetaInstance() = MangaDexSearchMetadata()

    override suspend fun parseIntoMetadata(
        metadata: MangaDexSearchMetadata,
        input: Triple<MangaDto, List<String>, StatisticsMangaDto>,
    ) {
        apiMangaParser.parseIntoMetadata(
            metadata,
            input.first,
            input.second,
            input.third,
            null,
            coverQuality(),
            altTitlesInDesc(),
            finalChapterInDesc(),
            preferExtensionLangTitle(),
        )
    }

    // LoginSource methods
    override val requiresLogin: Boolean = false

    override val twoFactorAuth = LoginSource.AuthSupport.NOT_SUPPORTED

    override fun isLogged(): Boolean {
        return MdUtil.isOAuthSet(preferenceStore)
    }

    override fun getUsername(): String {
        return if (isLogged()) "MangaDex" else ""
    }

    override fun getPassword(): String {
        return ""
    }

    override suspend fun login(authCode: String): Boolean {
        return loginHelper.login(authCode)
    }

    override suspend fun logout(): Boolean {
        return loginHelper.logout()
    }

    // FollowsSource methods
    override suspend fun fetchFollows(page: Int): MangasPage {
        return followsHandler.fetchFollows(page)
    }

    override suspend fun fetchAllFollows(): List<Pair<SManga, MangaDexSearchMetadata>> {
        return followsHandler.fetchAllFollows()
    }

    suspend fun updateFollowStatus(mangaID: String, followStatus: FollowStatus): Boolean {
        return followsHandler.updateFollowStatus(mangaID, followStatus)
    }

    suspend fun fetchTrackingInfo(url: String): Track {
        return followsHandler.fetchTrackingInfo(url)
    }

    suspend fun updateRating(track: Track): Boolean {
        return followsHandler.updateRating(track)
    }

    // RandomMangaSource method
    override suspend fun fetchRandomMangaUrl(): String {
        return mangaHandler.fetchRandomMangaId()
    }

    suspend fun getMangaSimilar(manga: SManga): MangasPage {
        return similarHandler.getSimilar(manga)
    }

    suspend fun getMangaRelated(manga: SManga): MangasPage {
        return similarHandler.getRelated(manga)
    }

    suspend fun getMangaMetadata(track: Track): SManga {
        return mangaHandler.getMangaMetadata(
            track,
            id,
            coverQuality(),
            tryUsingFirstVolumeCover(),
            altTitlesInDesc(),
            finalChapterInDesc(),
            preferExtensionLangTitle(),
        )
    }

    companion object {
        private const val dataSaverPref = "dataSaverV5"
        fun getDataSaverPreferenceKey(dexLang: String): String {
            return "${dataSaverPref}_$dexLang"
        }

        private const val standardHttpsPortPref = "usePort443"
        fun getStandardHttpsPreferenceKey(dexLang: String): String {
            return "${standardHttpsPortPref}_$dexLang"
        }

        private const val blockedGroupsPref = "blockedGroups"
        fun getBlockedGroupsPrefKey(dexLang: String): String {
            return "${blockedGroupsPref}_$dexLang"
        }

        private const val blockedUploaderPref = "blockedUploader"
        fun getBlockedUploaderPrefKey(dexLang: String): String {
            return "${blockedUploaderPref}_$dexLang"
        }

        private const val coverQualityPref = "thumbnailQuality"
        fun getCoverQualityPrefKey(dexLang: String): String {
            return "${coverQualityPref}_$dexLang"
        }

        private const val tryUsingFirstVolumeCoverPref = "tryUsingFirstVolumeCover"
        fun getTryUsingFirstVolumeCoverKey(dexLang: String): String {
            return "${tryUsingFirstVolumeCoverPref}_$dexLang"
        }

        private const val altTitlesInDescPref = "altTitlesInDesc"
        fun getAltTitlesInDescKey(dexLang: String): String {
            return "${altTitlesInDescPref}_$dexLang"
        }

        private const val finalChapterInDescPref = "finalChapterInDesc"
        fun getFinalChapterInDescPrefKey(dexLang: String): String {
            return "${finalChapterInDescPref}_$dexLang"
        }

        private const val preferExtensionLangTitlePref = "preferExtensionLangTitle"
        fun getPreferExtensionLangTitlePrefKey(dexLang: String): String {
            return "${preferExtensionLangTitlePref}_$dexLang"
        }
    }
}
