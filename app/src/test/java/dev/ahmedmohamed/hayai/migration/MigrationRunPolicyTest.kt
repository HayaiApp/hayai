package dev.ahmedmohamed.hayai.migration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationRunPolicyTest {
    @Test
    fun `automatic migration runs once and never loops after a durable result`() {
        assertTrue(MigrationRunPolicy.shouldRun(null, explicitRetry = false))
        assertFalse(MigrationRunPolicy.shouldRun("failed", explicitRetry = false))
        assertFalse(MigrationRunPolicy.shouldRun("running", explicitRetry = false))
        assertFalse(MigrationRunPolicy.shouldRun("complete", explicitRetry = false))
    }

    @Test
    fun `explicit retry only reopens a failed migration`() {
        assertTrue(MigrationRunPolicy.shouldRun(null, explicitRetry = true))
        assertTrue(MigrationRunPolicy.shouldRun("failed", explicitRetry = true))
        assertFalse(MigrationRunPolicy.shouldRun("running", explicitRetry = true))
        assertFalse(MigrationRunPolicy.shouldRun("complete", explicitRetry = true))
    }
}
