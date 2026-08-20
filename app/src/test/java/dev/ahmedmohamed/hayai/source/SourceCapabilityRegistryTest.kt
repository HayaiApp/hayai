package dev.ahmedmohamed.hayai.source

import eu.kanade.tachiyomi.source.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapabilityRegistryTest {
    @Test
    fun `recognizes enhanced source aliases`() {
        assertEquals(SourceFamily.EightMuses, descriptor("EroMuse").family)
        assertEquals(SourceFamily.Pururin, descriptor("Puruin").family)
        assertEquals(SourceFamily.Lanraragi, descriptor("LANraragi Library").family)
    }

    @Test
    fun `novel sources are routed by capability`() {
        assertTrue(descriptor("English Novel Source").capabilities.contains(SourceCapability.NovelText))
    }

    @Test
    fun `SY adult source aliases are gated by the master switch capability`() {
        listOf("Hentai2Read", "MyHentaiGallery", "Tsumino", "Luscious", "MultPorn").forEach { name ->
            assertTrue("$name should be adult", descriptor(name).capabilities.contains(SourceCapability.Adult))
        }
        assertTrue(descriptor("Unknown legacy source", 6905L).capabilities.contains(SourceCapability.Adult))
        assertTrue(descriptor("Unknown legacy source", 6913L).capabilities.contains(SourceCapability.Adult))
    }

    @Test
    fun `ordinary sources are not gated as adult`() {
        assertTrue(SourceCapability.Adult !in descriptor("MangaDex").capabilities)
        assertTrue(SourceCapability.Adult !in descriptor("Unknown source", 6914L).capabilities)
    }

    @Test
    fun `only EH and nHentai families honor the non-h genre override`() {
        listOf("E-Hentai", "ExHentai", "nHentai").forEach { name ->
            assertTrue(
                "$name should honor non-h",
                descriptor(name).capabilities.contains(SourceCapability.NonHentaiGenreOverride),
            )
        }
        assertTrue(SourceCapability.NonHentaiGenreOverride !in descriptor("Hentai2Read").capabilities)
    }

    private fun descriptor(
        name: String,
        id: Long = name.hashCode().toLong(),
    ) =
        SourceCapabilityRegistry.descriptor(
            object : Source {
                override val id = id
                override val name = name
            },
        )
}
