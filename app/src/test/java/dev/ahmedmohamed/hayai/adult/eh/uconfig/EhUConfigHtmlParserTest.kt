package dev.ahmedmohamed.hayai.adult.eh.uconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class EhUConfigHtmlParserTest {
    @Test
    fun `profile parser accepts only bounded application slots`() {
        val profiles = EhUConfigHtmlParser.profiles(
            """
            <select name="profile_set">
              <option value="0">Default</option>
              <option value="1">Hayai App</option>
              <option value="2">Personal</option>
              <option value="99">Invalid</option>
              <option value="broken">Broken</option>
            </select>
            """.trimIndent(),
        )

        assertEquals(listOf(1, 2), profiles.map { it.slot.value })
        assertEquals(listOf("Hayai App", "Personal"), profiles.map { it.name })
    }

    @Test
    fun `perks parser distinguishes purchased rows from purchase forms`() {
        val perks = EhUConfigHtmlParser.hathPerks(
            """
            <div class="stuffbox"><table>
              <tr><td>All Thumbs</td><td></td><td>Purchased</td></tr>
              <tr><td>Paging Enlargement III</td><td></td><td>Purchased</td></tr>
              <tr><td>Thumbs Up</td><td></td><td><form>Buy</form></td></tr>
            </table></div>
            """.trimIndent(),
        )

        assertTrue(perks.allThumbs)
        assertTrue(perks.pagingIII)
        assertFalse(perks.thumbsUp)
    }

    @Test
    fun `cookie parser preserves only expected cookies`() {
        val cookies = EhRemoteCookieParser.parse(
            listOf("sk=settings; Path=/", "s=session; Secure", "ignored=value", "hath_perks=perks"),
        )

        assertEquals(EhRemoteCookies("settings", "session", "perks"), cookies)
    }

    @Test
    fun `login and challenge pages are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EhUConfigHtmlParser.profiles("<form id=login></form>") }
        assertThrows(IllegalArgumentException::class.java) { EhUConfigHtmlParser.hathPerks("<title>Just a moment</title>") }
    }
}
