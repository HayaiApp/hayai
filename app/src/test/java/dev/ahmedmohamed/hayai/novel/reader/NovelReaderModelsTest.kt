package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelReaderModelsTest {
    @Test
    fun `tap modes match the J2K navigation region tables`() {
        val cases =
            listOf(
                TapCase(0, .5f, .1f, NovelTapAction.Previous),
                TapCase(0, .1f, .5f, NovelTapAction.Previous),
                TapCase(0, .9f, .5f, NovelTapAction.Next),
                TapCase(0, .5f, .9f, NovelTapAction.Next),
                TapCase(1, .5f, .1f, NovelTapAction.Previous),
                TapCase(1, .1f, .5f, NovelTapAction.Previous),
                TapCase(1, .5f, .5f, NovelTapAction.Menu),
                TapCase(1, .9f, .5f, NovelTapAction.Next),
                TapCase(1, .5f, .9f, NovelTapAction.Next),
                TapCase(2, .5f, .1f, NovelTapAction.Menu),
                TapCase(2, .1f, .8f, NovelTapAction.Previous),
                TapCase(2, .8f, .8f, NovelTapAction.Next),
                TapCase(3, .1f, .5f, NovelTapAction.Next),
                TapCase(3, .5f, .8f, NovelTapAction.Previous),
                TapCase(3, .5f, .5f, NovelTapAction.Menu),
                TapCase(4, .1f, .5f, NovelTapAction.Previous),
                TapCase(4, .5f, .5f, NovelTapAction.Menu),
                TapCase(4, .9f, .5f, NovelTapAction.Next),
                TapCase(5, .5f, .5f, NovelTapAction.Menu),
                TapCase(5, .1f, .9f, NovelTapAction.Menu),
                TapCase(6, .5f, .5f, NovelTapAction.Menu),
                TapCase(6, .2f, .5f, NovelTapAction.Menu),
            )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, NovelTapZones.action(case.mode, case.x, case.y, NovelTapInversion.NONE))
        }
    }

    @Test
    fun `tap inversion transforms regions before lookup`() {
        assertEquals(NovelTapAction.Next, NovelTapZones.action(4, .1f, .5f, NovelTapInversion.HORIZONTAL))
        assertEquals(NovelTapAction.Next, NovelTapZones.action(0, .5f, .1f, NovelTapInversion.VERTICAL))
    }

    @Test
    fun `bottom actions merge unknown and newly introduced values safely`() {
        val decoded = NovelBottomActions.deserialize("""[{"id":"tts","enabled":false},{"id":"removed","enabled":true}]""")

        assertFalse(decoded.first { it.action == NovelBottomAction.Tts }.enabled)
        assertFalse(decoded.first { it.action == NovelBottomAction.TtsViewport }.enabled)
        assertFalse(decoded.first { it.action == NovelBottomAction.TtsPreviousParagraph }.enabled)
        assertFalse(decoded.first { it.action == NovelBottomAction.TtsNextParagraph }.enabled)
        assertTrue(decoded.any { it.action == NovelBottomAction.Settings })
        assertEquals(decoded.size, decoded.distinctBy { it.action }.size)
    }

    private data class TapCase(val mode: Int, val x: Float, val y: Float, val expected: NovelTapAction)
}
