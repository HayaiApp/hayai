package eu.kanade.tachiyomi.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkHelperTest {
    @Test
    fun `default user agent is the requested modern Chrome identity`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36",
            NetworkHelper.DEFAULT_USER_AGENT,
        )
    }
}
