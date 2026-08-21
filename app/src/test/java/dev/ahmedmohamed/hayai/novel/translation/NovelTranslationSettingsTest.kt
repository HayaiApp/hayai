package dev.ahmedmohamed.hayai.novel.translation

import org.junit.Assert.assertThrows
import org.junit.Test

class NovelTranslationSettingsTest {
    @Test fun `google requires no endpoint`() {
        NovelTranslationSettings(targetLanguage = "ar").validate()
    }

    @Test fun `remote engine requires secure endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslationSettings(
                engine = NovelTranslationEngineId.LIBRE_TRANSLATE,
                endpoint = "http://remote.test",
            ).validate()
        }
    }

    @Test fun `remote engine rejects deceptive localhost host`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslationSettings(
                engine = NovelTranslationEngineId.LIBRE_TRANSLATE,
                endpoint = "http://localhost.attacker.test",
            ).validate()
        }
    }
}
