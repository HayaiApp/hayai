package dev.ahmedmohamed.hayai.adult.eh.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhTagCatalogTest {
    private val catalog = EhTagCatalog(listOf("female:big breasts", "female:sole female", "male:yaoi", "artist:someone"))

    @Test
    fun `substring matches survive namespaces and preserve exclude and OR prefixes`() {
        assertEquals(listOf("female:big breasts"), catalog.suggest("breasts", 100))
        assertEquals(listOf("-female:big breasts"), catalog.suggest("-BREASTS", 100))
        assertEquals(listOf("~female:big breasts"), catalog.suggest("~breasts", 100))
        assertTrue(catalog.suggest("+breasts", 100).isEmpty())
    }

    @Test
    fun `namespace entries lead into a qualified search instead of becoming tags`() {
        assertEquals(
            listOf("female:", "female:big breasts", "female:sole female"),
            catalog.suggest("fem", 100),
        )
        assertEquals(
            listOf("~female:", "~female:big breasts", "~female:sole female"),
            catalog.suggest("~fe", 100),
        )
        assertEquals(listOf("artist:", "artist:someone"), catalog.suggest("artist:", 100))
    }

    @Test
    fun `short inputs keep the suggestion list empty`() {
        assertTrue(catalog.suggest("", 100).isEmpty())
        assertTrue(catalog.suggest("fe", 100).isEmpty())
        assertTrue(catalog.suggest("-", 100).isEmpty())
    }

    @Test
    fun `suggestion cap includes namespace choices and retains catalog order`() {
        val largeCatalog = EhTagCatalog((1..110).map { "female:tag$it" })
        val matches = largeCatalog.suggest("female", 100)

        assertEquals(100, matches.size)
        assertEquals("female:", matches.first())
        assertEquals("female:tag99", matches.last())
        assertFalse("female:tag100" in matches)
    }
}
