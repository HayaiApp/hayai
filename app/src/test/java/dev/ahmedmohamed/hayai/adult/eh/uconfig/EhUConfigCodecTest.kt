package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.settings.EhHentaiAtHome
import dev.ahmedmohamed.hayai.adult.eh.settings.EhImageQuality
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguage
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguageSelection
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhUConfigCodecTest {
    @Test
    fun `form contains complete SY settings matrix`() {
        val languages = EhLanguage.entries.associateWithTo(linkedMapOf()) {
            EhLanguageSelection(original = it.originalCode != null, translated = true, rewritten = false)
        }
        val settings = settings(languages = languages, excluded = setOf(EhCategory.Doujinshi, EhCategory.Misc))

        val entries = EhUConfigFormCodec.entries(settings, EhHathPerks(allThumbs = true, pagingIII = true)).toMap()

        assertEquals("5", entries["xr"])
        assertEquals("1", entries["uh"])
        assertEquals("1", entries["tl"])
        assertEquals("1", entries["oi"])
        assertEquals("3", entries["tr"])
        assertEquals("3", entries["rc"])
        assertEquals("2", entries["dm"])
        assertEquals("0", entries["qb"])
        assertEquals("1", entries["pp"])
        assertEquals("-120", entries["ft"])
        assertEquals("75", entries["wt"])
        assertEquals("checked", entries["xl_1"])
        assertEquals("checked", entries["xl_1024"])
        assertEquals("", entries["xl_2048"])
        assertFalse("xl_0" in entries)
        assertEquals("1", entries["ct_doujinshi"])
        assertEquals("0", entries["ct_manga"])
        assertEquals("1", entries["ct_misc"])
        assertEquals("Apply", entries["apply"])
    }

    @Test
    fun `perk fallbacks and priorities match SY`() {
        assertEquals("0", EhHathPerks().thumbnailRowsValue)
        assertEquals("0", EhHathPerks().resultCountValue)
        assertEquals("3", EhHathPerks(moreThumbs = true, thumbsUp = true, allThumbs = true).thumbnailRowsValue)
        assertEquals("3", EhHathPerks(pagingI = true, pagingII = true, pagingIII = true).resultCountValue)
    }

    @Test
    fun `fingerprint is stable and changes with desired state`() {
        val first = settings()
        val same = settings()
        val changed = settings(excluded = setOf(EhCategory.Manga))

        assertEquals(first.fingerprint(), same.fingerprint())
        assertTrue(first.fingerprint() != changed.fingerprint())
        assertEquals(64, first.fingerprint().length)
    }

    private fun settings(
        languages: Map<EhLanguage, EhLanguageSelection> = EhLanguage.entries.associateWith { EhLanguageSelection() },
        excluded: Set<EhCategory> = emptySet(),
    ) = EhRemoteSettings(
        imageQuality = EhImageQuality.Size2400,
        hentaiAtHome = EhHentaiAtHome.DefaultOnly,
        japaneseTitles = true,
        originalImages = true,
        tagFilterThreshold = -120,
        tagWatchingThreshold = 75,
        languages = languages,
        excludedCategories = excluded,
    )
}

