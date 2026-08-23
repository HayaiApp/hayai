package dev.ahmedmohamed.hayai.source.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class SyPagePreviewLayoutTest {
    @Test
    fun `uses the same 120 dp floor calculation as SY`() {
        assertEquals(3, SyPagePreviewLayout.columns(1_344, 3f))
        assertEquals(2, SyPagePreviewLayout.columns(1_079, 3f))
    }

    @Test
    fun `narrow surfaces retain one preview column`() {
        assertEquals(1, SyPagePreviewLayout.columns(200, 3f))
    }
}
