package dev.ahmedmohamed.hayai.adult.eh.source

import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.network.EhTagQueryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EhTagSelectionCodecTest {
    @Test
    fun `chips preserve namespaces spaces exclude and or modes`() {
        val selected = listOf("female:big breasts", "-male:yaoi", "~artist:someone")

        val encoded = EhTagSelectionCodec.encode(selected)
        val terms = EhTagQueryCodec.parse(encoded)

        assertEquals("female:\"big breasts\" -male:yaoi ~artist:someone", encoded)
        assertEquals(listOf(EhTagMode.Include, EhTagMode.Exclude, EhTagMode.Any), terms.map { it.mode })
        assertEquals(selected, EhTagSelectionCodec.decode(encoded))
    }

    @Test
    fun `selection rejects duplicates blank input and more than eight tags`() {
        assertEquals(listOf("female:anal"), EhTagSelectionCodec.normalize(listOf(" female:anal ", "female:anal", "")))
        assertThrows(IllegalArgumentException::class.java) {
            EhTagSelectionCodec.normalize((1..9).map { "female:tag$it" })
        }
    }

    @Test
    fun `selection deduplicates tag casing`() {
        assertEquals(
            listOf("female:sole female"),
            EhTagSelectionCodec.normalize(listOf("female:sole female", "Female:Sole Female")),
        )
    }
}
