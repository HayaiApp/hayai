package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.network.EhHtmlParser
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader

interface EhFavoritesRemote {
    suspend fun snapshot(): EhRemoteFavoritesSnapshot
    suspend fun state(gallery: EhGalleryIdentity): EhFavoriteState?
    suspend fun setFavorite(desired: EhFavoriteState)
    suspend fun removeFavorite(expected: EhFavoriteState)
}

enum class EhFavoritesFailureReason {
    InvalidCategories,
    DuplicateGallery,
    TooManyFavorites,
    MissingCategories,
    PaginationExceeded,
    RequestFailed,
    ResponseTooLarge,
}

class EhFavoritesFailure(
    val reason: EhFavoritesFailureReason,
    val detail: Int? = null,
    diagnostic: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic ?: reason.name, cause)

class EhFavoritesHttpRemote(
    client: OkHttpClient,
    private val sessions: EhSessionStore,
    private val baseUrl: String = EhSite.ExHentai.baseUrl,
) : EhFavoritesRemote {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    override suspend fun snapshot(): EhRemoteFavoritesSnapshot {
        val favorites = linkedMapOf<EhGalleryIdentity, EhFavoriteState>()
        var categories: List<EhRemoteCategory>? = null
        var next: String? = null
        repeat(MAX_PAGES) {
            val url = "$baseUrl/favorites.php" + (next?.let { cursor -> "?next=$cursor" } ?: "")
            val html = get(url)
            if (categories == null) categories = EhFavoritesHtmlParser.categories(html)
            val page = EhHtmlParser.parseBrowse(html, url, EhSite.ExHentai)
            page.galleries.forEach { gallery ->
                val category = gallery.favoriteCategory ?: return@forEach
                val identity = EhGalleryIdentity(gallery.metadata.key.id.value, gallery.metadata.key.token.value)
                if (favorites.putIfAbsent(identity, EhFavoriteState(identity, gallery.metadata.title, EhFavoriteSlot(category))) != null) {
                    throw EhFavoritesFailure(EhFavoritesFailureReason.DuplicateGallery)
                }
                if (favorites.size > MAX_FAVORITES) throw EhFavoritesFailure(EhFavoritesFailureReason.TooManyFavorites)
            }
            next = page.nextCursor?.let { (it as? dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor.Gallery)?.id?.value }
            if (next == null) {
                val categoryList = categories ?: throw EhFavoritesFailure(EhFavoritesFailureReason.MissingCategories)
                return EhRemoteFavoritesSnapshot(categoryList, favorites, EhFavoritesFingerprint.create(categoryList, favorites.values))
            }
        }
        throw EhFavoritesFailure(EhFavoritesFailureReason.PaginationExceeded, MAX_PAGES)
    }

    override suspend fun state(gallery: EhGalleryIdentity): EhFavoriteState? = snapshot().favorites[gallery]

    override suspend fun setFavorite(desired: EhFavoriteState) {
        val popupUrl = popupUrl(desired.gallery)
        val currentHtml = get(popupUrl)
        val note = EhFavoritesHtmlParser.existingNote(currentHtml)
        post(
            popupUrl,
            FormBody.Builder()
                .add("favcat", desired.category.value.toString())
                .add("favnote", note)
                .add("apply", "Add to Favorites")
                .add("update", "1")
                .build(),
        )
    }

    override suspend fun removeFavorite(expected: EhFavoriteState) {
        post(
            "$baseUrl/favorites.php",
            FormBody.Builder()
                .add("ddact", "delete")
                .add("modifygids[]", expected.gallery.gid)
                .add("apply", "Apply")
                .build(),
        )
    }

    private suspend fun get(url: String): String = execute(
        Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).header("Cookie", sessions.cookieHeader(EhSite.ExHentai).value).build(),
        ) { response -> response.body.charStream().use { it.readBounded(EhFavoritesHtmlParser.MAX_DOCUMENT_CHARS) } }

    private suspend fun post(url: String, body: FormBody) {
        execute(
            Request.Builder().url(url).header("Cookie", sessions.cookieHeader(EhSite.ExHentai).value).post(body).build(),
        ) { response -> response.body.close() }
    }

    private suspend fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T = try {
        client.newCall(request).awaitSuccess().use(block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        throw EhFavoritesFailure(EhFavoritesFailureReason.RequestFailed, cause = failure)
    }

    private fun popupUrl(gallery: EhGalleryIdentity) = "$baseUrl/gallerypopups.php?gid=${gallery.gid}&t=${gallery.token}&act=addfav"

    private fun Reader.readBounded(maxChars: Int): String {
        val output = StringBuilder(minOf(maxChars, 8192))
        val buffer = CharArray(8192)
        while (output.length <= maxChars) {
            val remaining = maxChars + 1 - output.length
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        if (output.length > maxChars) throw EhFavoritesFailure(EhFavoritesFailureReason.ResponseTooLarge)
        return output.toString()
    }

    private companion object {
        const val MAX_PAGES = 2_000
        const val MAX_FAVORITES = 100_000
    }
}
