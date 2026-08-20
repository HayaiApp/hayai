package dev.ahmedmohamed.hayai.adult.eh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhLoginCookieParserTest {
    @Test
    fun `parser combines compatible cookies from the forum and gallery origins`() {
        val cookies =
            EhLoginCookieParser
                .parse(
                    "ipb_member_id=12345; ipb_pass_hash=abcDEF012345",
                    "igneous=igneous-token; nw=1",
                ).getOrThrow()

        assertEquals(EhCredentials("12345", "abcDEF012345", "igneous-token"), cookies.toCredentials().getOrThrow())
    }

    @Test
    fun `conflicting login cookies are rejected`() {
        val result = EhLoginCookieParser.parse("ipb_member_id=123", "ipb_member_id=456")

        assertTrue(result.isFailure)
    }

    @Test
    fun `manual igneous rejects header injection`() {
        val cookies = EhLoginCookieParser.parse("ipb_member_id=123; ipb_pass_hash=abc123").getOrThrow()

        assertTrue(cookies.toCredentials("valid; injected=true").isFailure)
    }
}
