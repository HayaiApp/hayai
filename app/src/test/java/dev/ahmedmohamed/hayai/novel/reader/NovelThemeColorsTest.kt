package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelThemeColorsTest {
    @Test
    fun `custom theme uses custom colors and independent defaults`() {
        assertEquals(
            0xFF123456.toInt() to 0xFFABCDEF.toInt(),
            NovelThemeColors.resolve("custom", 0xFF123456.toInt(), 0xFFABCDEF.toInt(), 1, 2),
        )
        assertEquals(
            NovelThemeColors.LIGHT_BACKGROUND to NovelThemeColors.LIGHT_TEXT,
            NovelThemeColors.resolve("custom", 0, 0, 1, 2),
        )
    }

    @Test
    fun `preset themes ignore remembered custom colors`() {
        assertEquals(
            NovelThemeColors.DARK_BACKGROUND to NovelThemeColors.DARK_TEXT,
            NovelThemeColors.resolve("dark", 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 1, 2),
        )
        assertEquals(11 to 22, NovelThemeColors.resolve("app", 1, 2, 11, 22))
    }

    @Test
    fun `hex parser accepts rgb shorthand and rejects malformed input`() {
        assertEquals(0xFFAABBCC.toInt(), NovelThemeColors.parseRgb("#abc"))
        assertEquals(0xFF12ABEF.toInt(), NovelThemeColors.parseRgb("12ABef"))
        assertNull(NovelThemeColors.parseRgb("#12"))
        assertNull(NovelThemeColors.parseRgb("#GGGGGG"))
        assertEquals("#12ABEF", NovelThemeColors.formatRgb(0x7F12ABEF))
    }
}
