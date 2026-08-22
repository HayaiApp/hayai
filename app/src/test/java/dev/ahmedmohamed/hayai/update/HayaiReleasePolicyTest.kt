package dev.ahmedmohamed.hayai.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HayaiReleasePolicyTest {
    @Test
    fun `nightly uses the nightly repository and numeric tags`() {
        val release = HayaiReleasePolicy.current(true, "1.8.1-r6658", "6658")

        assertEquals("HayaiApp/hayai-nightly", release.repository)
        assertEquals("r6658", release.releaseTag)
        assertTrue(release.isNewer("r6659"))
        assertFalse(release.isNewer("r6658"))
        assertFalse(release.isNewer("v9.0.0"))
    }

    @Test
    fun `stable comparison handles releases betas and malformed tags`() {
        val stable = HayaiReleasePolicy.current(false, "1.8.1", "0")
        val beta = HayaiReleasePolicy.current(false, "1.8.1-b2", "0")

        assertTrue(stable.isNewer("v1.8.2"))
        assertFalse(stable.isNewer("v1.8.1-b9"))
        assertTrue(beta.isNewer("v1.8.1"))
        assertTrue(beta.isNewer("v1.8.1-b3"))
        assertFalse(beta.isNewer("not-a-version"))
    }

    @Test
    fun `apk selection prefers the device ABI then the universal Hayai build`() {
        val links =
            listOf(
                "https://example.test/notes.txt",
                "https://example.test/hayai-r6658.apk",
                "https://example.test/hayai-arm64-v8a-r6658.apk",
            )

        assertEquals(links[2], HayaiReleasePolicy.selectApk(links, "arm64-v8a"))
        assertEquals(links[1], HayaiReleasePolicy.selectApk(links, "riscv64"))
    }
}
