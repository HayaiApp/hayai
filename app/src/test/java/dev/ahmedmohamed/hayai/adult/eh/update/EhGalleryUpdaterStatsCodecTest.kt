package dev.ahmedmohamed.hayai.adult.eh.update

import org.junit.Assert.assertEquals
import org.junit.Test

class EhGalleryUpdaterStatsCodecTest {
    @Test
    fun `statistics round trip without dropping failure or cutoff state`() {
        val stats = EhGalleryUpdaterStats(1, 2, 50, 5, 2, 3, 1, 1, 4, 5, 6, true)
        assertEquals(stats, EhGalleryUpdaterStatsCodec.decode(EhGalleryUpdaterStatsCodec.encode(stats)))
    }

    @Test
    fun `policy rejects intervals WorkManager cannot represent`() {
        listOf(-1, 169).forEach { value ->
            runCatching { EhGalleryUpdatePolicy(intervalHours = value) }
                .onSuccess { throw AssertionError("Expected interval $value to be rejected") }
        }
        assertEquals(0, EhGalleryUpdatePolicy(intervalHours = 0).intervalHours)
    }
}
