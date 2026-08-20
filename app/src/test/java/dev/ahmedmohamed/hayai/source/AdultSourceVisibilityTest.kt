package dev.ahmedmohamed.hayai.source

import eu.kanade.tachiyomi.source.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultSourceVisibilityTest {
    @Test
    fun `enabled adult features include ordinary and adult sources`() {
        assertTrue(AdultSourceVisibility.includes(source("MangaDex"), adultFeaturesEnabled = true))
        assertTrue(AdultSourceVisibility.includes(source("nHentai"), adultFeaturesEnabled = true))
    }

    @Test
    fun `disabled adult features exclude only adult sources from discovery`() {
        assertTrue(AdultSourceVisibility.includes(source("MangaDex"), adultFeaturesEnabled = false))
        assertFalse(AdultSourceVisibility.includes(source("nHentai"), adultFeaturesEnabled = false))
        assertFalse(AdultSourceVisibility.includes(source("Hentai2Read"), adultFeaturesEnabled = false))
    }

    private fun source(name: String) =
        object : Source {
            override val id = name.hashCode().toLong()
            override val name = name
        }
}
