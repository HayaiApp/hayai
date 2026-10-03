package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelTtsLanguageTest {
    @Test fun `explicit language takes priority over translation source and device`() {
        assertEquals("fr-CA", NovelTtsLanguage.resolve("fr-CA", "ar", "ja", "en-US"))
    }

    @Test fun `automatic playback speaks the displayed translation before source language`() {
        assertEquals("ar", NovelTtsLanguage.resolve("auto", "ar", "ja", "en-US"))
    }

    @Test fun `automatic original text uses source language instead of English device default`() {
        assertEquals("zh", NovelTtsLanguage.resolve("auto", "", "zh", "en-US"))
    }

    @Test fun `sources without a language fall back to device language`() {
        assertEquals("pt-BR", NovelTtsLanguage.resolve("auto", "auto", "all", "pt-BR"))
        assertEquals("de", NovelTtsLanguage.resolve("", "", "other", "de"))
    }
}
