package dev.ahmedmohamed.hayai.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibleEnumPreferenceTest {
    private enum class CurrentValue {
        Default,
        Preserved,
    }

    private val serializer =
        CompatibleEnumSerializer(
            values = CurrentValue.entries.toTypedArray(),
            defaultValue = CurrentValue.Default,
            legacyAliases = mapOf("Removed" to CurrentValue.Preserved),
        )

    @Test
    fun `current values round trip unchanged`() {
        CurrentValue.entries.forEach { value ->
            assertEquals(value, serializer.deserialize(serializer.serialize(value)))
        }
    }

    @Test
    fun `legacy aliases preserve the closest supported behavior`() {
        assertEquals(CurrentValue.Preserved, serializer.deserialize("Removed"))
    }

    @Test
    fun `unknown values use the declared default without throwing`() {
        assertEquals(CurrentValue.Default, serializer.deserialize("FutureValue"))
    }

    @Test
    fun `a legacy alias cannot override a current value`() {
        val conflicting =
            CompatibleEnumSerializer(
                values = CurrentValue.entries.toTypedArray(),
                defaultValue = CurrentValue.Default,
                legacyAliases = mapOf("Preserved" to CurrentValue.Default),
            )

        assertEquals(CurrentValue.Preserved, conflicting.deserialize("Preserved"))
    }

    @Test
    fun `removed legacy Hayai themes cannot crash preference decoding`() {
        val removedThemes =
            listOf("DOKI", "SAKURA", "PINK_ROMANCE", "SUMI_E", "KIMONO", "WAGASHI", "NORDIC", "ROSE")

        removedThemes.forEach { value ->
            assertEquals(CurrentValue.Default, serializer.deserialize(value))
        }
    }
}
