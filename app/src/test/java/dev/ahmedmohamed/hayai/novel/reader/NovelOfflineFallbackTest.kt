package dev.ahmedmohamed.hayai.novel.reader

import eu.kanade.tachiyomi.network.HttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NovelOfflineFallbackTest {
    @Test
    fun `offline translation fallback accepts transport and recoverable http failures`() {
        assertTrue(IOException("offline").allowsOfflineTranslationFallback())
        assertTrue(HttpException(408).allowsOfflineTranslationFallback())
        assertTrue(HttpException(429).allowsOfflineTranslationFallback())
        assertTrue(HttpException(503).allowsOfflineTranslationFallback())
    }

    @Test
    fun `offline translation fallback does not mask authentication or source failures`() {
        assertFalse(HttpException(401).allowsOfflineTranslationFallback())
        assertFalse(HttpException(403).allowsOfflineTranslationFallback())
        assertFalse(HttpException(404).allowsOfflineTranslationFallback())
        assertFalse(IllegalStateException("parser failed").allowsOfflineTranslationFallback())
    }
}
