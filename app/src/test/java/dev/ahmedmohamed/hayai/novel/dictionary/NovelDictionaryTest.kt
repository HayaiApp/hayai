package dev.ahmedmohamed.hayai.novel.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test

class NovelDictionaryTest {
    @Test fun `all web providers use https`() {
        NovelDictionaryProvider.entries.filter { it.name.startsWith("WEB_") }.forEach { assertTrue(it.uri("two words").toString().startsWith("https://")) }
    }
}
