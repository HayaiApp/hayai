package dev.ahmedmohamed.hayai.novel.tracker.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RanobeDbSuperFormTest {
    @Test
    fun `book form maps status score and dates to expected slots`() {
        val encoded = RanobeDbSuperForm.encodeBook(NovelReadingStatus.Completed, 9.7f, "2026-01-02", "2026-02-03", "add")
        val values = Json.parseToJsonElement(encoded) as JsonArray

        assertEquals(2, values[3].jsonPrimitive.content.toInt())
        assertEquals("Finished", values[6].jsonPrimitive.content)
        assertEquals(9, values[7].jsonPrimitive.content.toInt())
        assertEquals("2026-01-02", values[8].jsonPrimitive.content)
        assertEquals("2026-02-03", values[9].jsonPrimitive.content)
        assertEquals("add", values[11].jsonPrimitive.content)
    }

    @Test
    fun `series delete form keeps required slot count and action`() {
        val values = Json.parseToJsonElement(RanobeDbSuperForm.encodeSeries(NovelReadingStatus.Dropped, 0f, "delete")) as JsonArray

        assertEquals(18, values.size)
        assertTrue(values[13].toString() == "null")
        assertEquals("delete", values[17].jsonPrimitive.content)
    }
}

