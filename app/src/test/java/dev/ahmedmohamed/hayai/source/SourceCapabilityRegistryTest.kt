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

    private fun descriptor(name: String) =
        SourceCapabilityRegistry.descriptor(
            object : Source {
                override val id = name.hashCode().toLong()
                override val name = name
            },
        )
}
