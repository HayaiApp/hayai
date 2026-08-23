package dev.ahmedmohamed.hayai.novel.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelLookupRouterTest {
    @Test fun `query rejects blank text`() {
        val error = NovelSelectionQuery.parse(" \n\t ").exceptionOrNull() as NovelSelectionQueryException

        assertEquals(NovelSelectionQueryFailure.Blank, error.failure)
    }

    @Test fun `query rejects an oversized unicode url value`() {
        val error = NovelSelectionQuery.parse("界".repeat(683)).exceptionOrNull() as NovelSelectionQueryException

        assertEquals(NovelSelectionQueryFailure.TooLong, error.failure)
    }

    @Test fun `search preserves unicode through url encoding`() {
        val route = NovelLookupRouter.route(request(NovelLookupAction.SearchWeb, "辞書 café \uD83D\uDE80")) as NovelLookupRoute.Web

        assertEquals("辞書 café \uD83D\uDE80", route.webUrl.queryParameter("q"))
        assertEquals("https", route.webUrl.scheme)
    }

    @Test fun `definition uses a distinct define query`() {
        val route = NovelLookupRouter.route(request(NovelLookupAction.Define, "serendipity")) as NovelLookupRoute.Web

        assertEquals("define serendipity", route.webUrl.queryParameter("q"))
    }

    @Test fun `google translate tries process text then send before web`() {
        val route =
            NovelLookupRouter.route(
                request(NovelLookupAction.GoogleTranslate, "مرحبا", source = "ar", target = "en"),
            ) as NovelLookupRoute.AppThenWeb

        assertEquals(
            listOf(NovelLookupAppIntent.GoogleTranslateProcessText, NovelLookupAppIntent.GoogleTranslateSend),
            route.appIntents,
        )
        assertEquals("ar", route.webUrl.queryParameter("sl"))
        assertEquals("en", route.webUrl.queryParameter("tl"))
        assertEquals("مرحبا", route.webUrl.queryParameter("text"))
        assertEquals("translate", route.webUrl.queryParameter("op"))
        assertTrue(route.webUrl.isHttps)
    }

    @Test fun `translate web fallback repairs invalid stored languages`() {
        val route =
            NovelLookupRouter.route(
                request(NovelLookupAction.GoogleTranslate, "text", source = "invalid!", target = "auto"),
            ) as NovelLookupRoute.AppThenWeb

        assertEquals("auto", route.webUrl.queryParameter("sl"))
        assertEquals("en", route.webUrl.queryParameter("tl"))
    }

    private fun request(
        action: NovelLookupAction,
        text: String,
        source: String = "auto",
        target: String = "en",
    ) = NovelLookupRequest(action, NovelSelectionQuery.parse(text).getOrThrow(), source, target)
}
