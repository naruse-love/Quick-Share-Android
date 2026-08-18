package com.quickshare.android.e2e

import com.quickshare.android.e2e.harness.LoopbackHarness
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * End-to-End Remote File Operations Tests (LIST_FILES, MKDIR, DELETE_FILE, SHUTDOWN).
 */
class RemoteFileOpsE2ETest {

    @Test
    fun testListRemoteFiles() {
        LoopbackHarness().use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val serverDir = harness.server.sandboxDir
            harness.createTestFile(serverDir, "sample1.txt", 100L)
            harness.createTestFile(serverDir, "sample2.jpg", 5000L)
            File(serverDir, "subfolder").mkdirs()

            val remoteFiles = harness.client.listFiles("/")
            assertNotNull("Remote files list should not be null", remoteFiles)
            assertEquals(3, remoteFiles?.size)

            val fileNames = remoteFiles?.map { it.name } ?: emptyList()
            assertTrue(fileNames.contains("sample1.txt"))
            assertTrue(fileNames.contains("sample2.jpg"))
            assertTrue(fileNames.contains("subfolder"))

            val subfolder = remoteFiles?.first { it.name == "subfolder" }
            assertTrue(subfolder?.isDirectory == true)
        }
    }

    @Test
    fun testListNonExistentDirectoryReturnsNull() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val nonExistent = harness.client.listFiles("/invalid_dir_path_xyz")
            assertNull("Expected null for non-existent path", nonExistent)
        }
    }

    @Test
    fun testRemoteMkdir() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            val success = harness.client.mkdir("/", "NewRemoteDir_2026")
            assertTrue("MKDIR RPC failed", success)

            val created = File(harness.server.sandboxDir, "NewRemoteDir_2026")
            assertTrue("Directory was not created on server", created.exists() && created.isDirectory)
        }
    }

    @Test
    fun testRemoteDeleteFileAndFolder() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            val serverDir = harness.server.sandboxDir
            val fileToDelete = harness.createTestFile(serverDir, "to_delete.txt", 50L)
            val folderToDelete = File(serverDir, "folder_to_delete").apply { mkdirs() }
            harness.createTestFile(folderToDelete, "inner.txt", 20L)

            assertTrue(fileToDelete.exists())
            assertTrue(folderToDelete.exists())

            // Delete file
            assertTrue(harness.client.deleteFile("to_delete.txt"))
            assertFalse(fileToDelete.exists())

            // Delete directory recursively
            assertTrue(harness.client.deleteFile("folder_to_delete"))
            assertFalse(folderToDelete.exists())
        }
    }

    @Test
    fun testGracefulShutdown() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            harness.client.shutdown()
            // Wait briefly for server teardown
            Thread.sleep(100)
        }
    }
}
