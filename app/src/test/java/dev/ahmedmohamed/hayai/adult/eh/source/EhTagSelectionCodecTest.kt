package dev.ahmedmohamed.hayai.adult.eh.source

import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
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

    @Test
    fun `namespace drilldown cannot enter a saved filter as an empty tag`() {
        for (namespace in listOf("female:", "-male:", "~artist:")) {
            assertThrows(EhFailure.MalformedDocument::class.java) { EhTagSelectionCodec.encode(listOf(namespace)) }
        }
    }

    @Test
    fun `duplicate selection at the limit succeeds without creating a ninth chip`() {
        val selections = (1..8).map { "female:tag$it" }

        assertEquals(selections, EhTagSelectionCodec.normalize(selections + "Female:Tag1"))
    }

    @Test
    fun `removing a chip preserves the remaining multiword tags and modes`() {
        val selections = listOf("female:big breasts", "-male:yaoi", "~artist:some artist")
        val remaining = EhTagSelectionCodec.decode(EhTagSelectionCodec.encode(selections - "-male:yaoi"))

        assertEquals(listOf("female:big breasts", "~artist:some artist"), remaining)
        assertEquals(listOf(EhTagMode.Include, EhTagMode.Any), EhTagQueryCodec.parse(EhTagSelectionCodec.encode(remaining)).map { it.mode })
    }
}
