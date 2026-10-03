package dev.ahmedmohamed.hayai.novel.compat

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation

class SourceTrackerCompatibilityTest {
    @Test
    fun `compile-only tracker source can link inherited defaults and suspend callbacks`() = runBlocking {
        val source = TrackerExtensionShape()
        assertTrue(source.supportsChapterTracking)
        assertFalse(source.supportsFavoritesTracking)
        source.onChaptersRead(SManga.create(), emptyList(), emptyList(), emptyList())
        source.onChaptersUnread(SManga.create(), emptyList(), emptyList(), emptyList())
        source.onFavorited(SManga.create(), emptyList())
        source.onUnfavorited(SManga.create(), emptyList())
    }

    @Test
    fun `host exposes exact tracker binary names and Kotlin legacy default delegates`() {
        val type = Class.forName("eu.kanade.tachiyomi.source.SourceTracker")
        assertEquals(SourceTracker::class.java, type)
        type.getMethod("getSupportsChapterTracking")
        type.getMethod("getSupportsFavoritesTracking")
        type.getMethod("onChaptersRead", SManga::class.java, List::class.java, List::class.java, List::class.java, Continuation::class.java)
        type.getMethod("onUnfavorited", SManga::class.java, List::class.java, Continuation::class.java)
        Class.forName("eu.kanade.tachiyomi.source.SourceTracker\$DefaultImpls")
            .getMethod("getSupportsFavoritesTracking", type)
    }

    private class TrackerExtensionShape : Source, SourceTracker {
        override val id = 7L
        override val name = "WTR-LAB fixture"
    }

    @Test
    fun `novel phoenix compile-only pacing interface links inherited defaults`() {
        val source = PhoenixExtensionShape()
        assertEquals(1000L, source.recommendedDelayMillis)
        assertEquals(1, source.recommendedPermits)
        val type = Class.forName("eu.kanade.tachiyomi.source.RateLimited")
        type.getMethod("getMinimumDelayMillis")
        Class.forName("eu.kanade.tachiyomi.source.RateLimited\$DefaultImpls")
            .getMethod("getRecommendedDelayMillis", type)
    }

    private class PhoenixExtensionShape : Source, RateLimited {
        override val id = 7165539527173321330L
        override val name = "Novel Phoenix fixture"
        override val minimumDelayMillis = 1000L
    }
}
