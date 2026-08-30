package dev.ahmedmohamed.hayai.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageLocationAccessTest {
    @Test
    fun `accepts the root and descendants but rejects sibling prefixes`() {
        val root = File("build/test-storage/app")

        assertTrue(StorageLocationAccess.isInside(root, root))
        assertTrue(StorageLocationAccess.isInside(File(root, "downloads/novels"), root))
        assertFalse(StorageLocationAccess.isInside(File("build/test-storage/application"), root))
        assertFalse(StorageLocationAccess.isInside(root, null))
    }
}
