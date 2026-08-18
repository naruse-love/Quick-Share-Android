package com.quickshare.android.model

import org.junit.Assert.*
import org.junit.Test

class QuickShareDirectoryPathTest {

    @Test
    fun testUnixNormalizationEmptyAndRoot() {
        val dEmpty = QuickShareDirectory("", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/", dEmpty.path)

        val dRoot = QuickShareDirectory("/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/", dRoot.path)
    }

    @Test
    fun testUnixNormalizationStandardPaths() {
        val d1 = QuickShareDirectory("/sdcard/Download", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/sdcard/Download/", d1.path)

        val d2 = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/sdcard/Download/", d2.path)

        val d3 = QuickShareDirectory("/a/b/c", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/a/b/c/", d3.path)
    }

    @Test
    fun testWindowsNormalizationDriveLetters() {
        val dC = QuickShareDirectory("C:", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("C:\\", dC.path)

        val dCSlash = QuickShareDirectory("C:\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("C:\\", dCSlash.path)

        val dD = QuickShareDirectory("D:", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("D:\\", dD.path)
    }

    @Test
    fun testWindowsNormalizationBackslash() {
        val d = QuickShareDirectory("C:/Users/Admin/Downloads", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("C:\\Users\\Admin\\Downloads\\", d.path)

        val d2 = QuickShareDirectory("C:\\Users\\Admin\\Downloads\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("C:\\Users\\Admin\\Downloads\\", d2.path)
    }

    @Test
    fun testUnixParentResolution() {
        val d1 = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val p1 = d1.parent()
        assertNotNull(p1)
        assertEquals("/sdcard/", p1!!.path)

        val p2 = p1.parent()
        assertNotNull(p2)
        assertEquals("/", p2!!.path)

        val p3 = p2.parent()
        assertNull(p3)
    }

    @Test
    fun testWindowsParentResolution() {
        val d1 = QuickShareDirectory("C:\\Users\\Admin\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        val p1 = d1.parent()
        assertNotNull(p1)
        assertEquals("C:\\Users\\", p1!!.path)

        val p2 = p1.parent()
        assertNotNull(p2)
        assertEquals("/", p2!!.path)

        val p3 = p2.parent()
        assertNull(p3)
    }

    @Test
    fun testAppendChildUnix() {
        val base = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val child1 = base.append("Music")
        assertEquals("/sdcard/Download/Music/", child1.path)

        val child2 = base.append("/Videos/2026")
        assertEquals("/sdcard/Download/Videos/2026/", child2.path)

        val childEmpty = base.append("")
        assertEquals("/sdcard/Download/", childEmpty.path)
    }

    @Test
    fun testAppendChildWindows() {
        val base = QuickShareDirectory("C:\\Users\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        val child1 = base.append("Docs\\Sub")
        assertEquals("C:\\Users\\Docs\\Sub\\", child1.path)

        val child2 = base.append("\\Downloads\\Music")
        assertEquals("C:\\Users\\Downloads\\Music\\", child2.path)
    }

    @Test
    fun testTransferPathUnixToWindows() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Received\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val filePath = "/sdcard/Download/subfolder/file.txt"
        val transferPath = localDir.generateTransferPath(filePath, remoteDir)
        assertEquals("D:\\Received\\subfolder\\file.txt", transferPath)
    }

    @Test
    fun testTransferPathWindowsToUnix() {
        val localDir = QuickShareDirectory("C:\\Data\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        val remoteDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)

        val filePath = "C:\\Data\\docs\\report.pdf"
        val transferPath = localDir.generateTransferPath(filePath, remoteDir)
        assertEquals("/sdcard/Download/docs/report.pdf", transferPath)
    }

    @Test
    fun testIllegalCharacterSanitization() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Received\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val invalidFile = "/sdcard/Download/report:2026*final?.pdf"
        val transferPath = localDir.generateTransferPath(invalidFile, remoteDir)
        assertEquals("D:\\Received\\report_2026_final_.pdf", transferPath)

        val extremeFile = "/sdcard/Download/<data>|\"foo\".bin"
        val extremePath = localDir.generateTransferPath(extremeFile, remoteDir)
        assertEquals("D:\\Received\\_data___foo_.bin", extremePath)
    }

    @Test
    fun testRelativeFileOutsideBaseDir() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Received\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val outsideFile = "/sdcard/DCIM/photo.jpg"
        val transferPath = localDir.generateTransferPath(outsideFile, remoteDir)
        assertEquals("D:\\Received\\sdcard\\DCIM\\photo.jpg", transferPath)
    }

    @Test
    fun testEqualityAndHashCode() {
        val dir1 = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val dir2 = QuickShareDirectory("/sdcard/Download", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val dirWin = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        assertEquals(dir1, dir2)
        assertEquals(dir1.hashCode(), dir2.hashCode())
        assertNotEquals(dir1, dirWin)
        assertTrue(dir1.toString().contains("UNIX"))
        assertTrue(dirWin.toString().contains("WINDOWS"))
    }
}
