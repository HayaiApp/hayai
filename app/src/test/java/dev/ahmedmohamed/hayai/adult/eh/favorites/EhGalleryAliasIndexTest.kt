package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryAlias
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EhGalleryAliasIndexTest {
    @Test
    fun `alias chains converge on one canonical gallery`() {
        val canonical = gallery(1)
        val middle = gallery(2)
        val alternate = gallery(3)
        val index = EhGalleryAliasIndex(listOf(EhGalleryAlias(canonical, middle), EhGalleryAlias(middle, alternate)))

        assertEquals(canonical, index.canonical(alternate))
        assertEquals(setOf(canonical, middle, alternate), index.equivalents(alternate))
    }

    @Test
    fun `cycles and conflicting owners are rejected`() {
        val first = gallery(1)
        val second = gallery(2)
        val third = gallery(3)

        assertThrows(IllegalArgumentException::class.java) {
            EhGalleryAliasIndex(listOf(EhGalleryAlias(first, second), EhGalleryAlias(second, first)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EhGalleryAliasIndex(listOf(EhGalleryAlias(first, third), EhGalleryAlias(second, third)))
        }
    }

    private fun gallery(id: Int) = EhGalleryIdentity(id.toString(), "token$id")
}

