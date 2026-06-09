package eu.kanade.tachiyomi.data.translation

object TranslationCachePolicy {
    fun shouldServeCached(cached: TranslationCache.CachedTranslation?): Boolean = cached != null
    fun shouldSkipAutoEnqueue(cached: TranslationCache.CachedTranslation?): Boolean = cached != null
}
