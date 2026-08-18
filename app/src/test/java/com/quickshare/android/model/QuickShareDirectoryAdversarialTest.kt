package com.quickshare.android.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Adversarial edge case and stress tests for QuickShareDirectory, TrafficInfo, and boundary conditions.
 */
class QuickShareDirectoryAdversarialTest {

    // ==========================================
    // 1. PATH TRAVERSAL & MALICIOUS PATH TESTS
    // ==========================================

    @Test
    fun testPathTraversalDotDotUnixToWindows() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Target\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        // Traversal attempting to escape base dir
        val maliciousPath = "/sdcard/Download/../../etc/passwd"
        val transferPath = localDir.generateTransferPath(maliciousPath, remoteDir)
        // Sanitization keeps segments intact but preserves remote separator
        assertEquals("D:\\Target\\..\\..\\etc\\passwd", transferPath)
    }

    @Test
    fun testPathTraversalWindowsBackslashesOnUnixLocal() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Target\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        // Windows backslash path traversal injected into Unix local
        val maliciousPath = "/sdcard/Download/..\\..\\Windows\\System32\\calc.exe"
        val transferPath = localDir.generateTransferPath(maliciousPath, remoteDir)
        // On Unix local, backslash is sanitized to _ by ILLEGAL_CHARS_REGEX
        assertEquals("D:\\Target\\.._.._Windows_System32_calc.exe", transferPath)
    }

    @Test
    fun testDeepRelativePathTraversalOutsideLocalFolder() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("C:\\Data\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val externalFile = "../../../../var/log/syslog"
        val transferPath = localDir.generateTransferPath(externalFile, remoteDir)
        assertEquals("C:\\Data\\..\\..\\..\\..\\var\\log\\syslog", transferPath)
    }

    // ==========================================
    // 2. ILLEGAL CHARACTERS & INJECTION TESTS
    // ==========================================

    @Test
    fun testAllWindowsForbiddenCharactersSanitized() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("E:\\Store\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        // Characters: \ : * ? " < > |
        val forbiddenFile = "/sdcard/Download/test:name*with?bad\"chars<and>pipes|end.txt"
        val transferPath = localDir.generateTransferPath(forbiddenFile, remoteDir)
        assertEquals("E:\\Store\\test_name_with_bad_chars_and_pipes_end.txt", transferPath)
    }

    @Test
    fun testConsecutiveIllegalCharacters() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("E:\\Store\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val heavyIllegal = "/sdcard/Download/<<<:::***???>>>|||\"\"\"file.bin"
        val transferPath = localDir.generateTransferPath(heavyIllegal, remoteDir)
        assertEquals("E:\\Store\\_____________________file.bin", transferPath)
    }

    @Test
    fun testNullAndControlCharactersInPath() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("/data/local/tmp/", QuickShareDirectory.FILE_SYSTEM_UNIX)

        val controlFile = "/sdcard/Download/\u0000hidden\t\r\nfile.txt"
        val transferPath = localDir.generateTransferPath(controlFile, remoteDir)
        assertEquals("/data/local/tmp/\u0000hidden\t\r\nfile.txt", transferPath)
    }

    // ==========================================
    // 3. ROOT DIRECTORY & BOUNDARY TESTS
    // ==========================================

    @Test
    fun testTransferFromRootDirectoryUnixToWindows() {
        val localDir = QuickShareDirectory("/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("C:\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val file = "/etc/hosts"
        val transferPath = localDir.generateTransferPath(file, remoteDir)
        assertEquals("C:\\etc\\hosts", transferPath)
    }

    @Test
    fun testTransferRootFileDirectly() {
        val localDir = QuickShareDirectory("/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Backup\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        val rootTransfer = localDir.generateTransferPath("/", remoteDir)
        assertEquals("D:\\Backup\\", rootTransfer)
    }

    @Test
    fun testWindowsDriveLetterRootTransfers() {
        val localDir = QuickShareDirectory("C:\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        val remoteDir = QuickShareDirectory("/sdcard/Transferred/", QuickShareDirectory.FILE_SYSTEM_UNIX)

        val file1 = "C:\\Windows\\explorer.exe"
        val res1 = localDir.generateTransferPath(file1, remoteDir)
        assertEquals("/sdcard/Transferred/Windows/explorer.exe", res1)

        val file2 = "C:/Windows/System32/cmd.exe"
        val res2 = localDir.generateTransferPath(file2, remoteDir)
        assertEquals("/sdcard/Transferred/Windows/System32/cmd.exe", res2)
    }

    @Test
    fun testParentOfRootTransitions() {
        val unixRoot = QuickShareDirectory("/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertNull(unixRoot.parent())

        val winRoot = QuickShareDirectory("C:\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        val parent1 = winRoot.parent()
        assertNotNull(parent1)
        assertEquals("/", parent1!!.path)
        assertNull(parent1.parent())

        val winDriveNoSlash = QuickShareDirectory("C:", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("C:\\", winDriveNoSlash.path)
        val winParent = winDriveNoSlash.parent()
        assertNotNull(winParent)
        assertEquals("/", winParent!!.path)
    }

    // ==========================================
    // 4. TRAILING SLASHES & REDUNDANT SEPARATORS
    // ==========================================

    @Test
    fun testMultipleConsecutiveSlashesNormalization() {
        val d1 = QuickShareDirectory("///sdcard///Download///", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertTrue(d1.path.startsWith("/"))
        assertTrue(d1.path.endsWith("/"))

        val dWin = QuickShareDirectory("C:\\\\Users\\\\Admin\\\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertTrue(dWin.path.endsWith("\\"))
    }

    @Test
    fun testGenerateTransferPathWithConsecutiveSlashes() {
        val localDir = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val remoteDir = QuickShareDirectory("D:\\Target\\", QuickShareDirectory.FILE_SYSTEM_WINDOWS)

        // Multiple consecutive separators in relative file path
        val fileWithSlashes = "/sdcard/Download///folderA///folderB///file.dat"
        val transferPath = localDir.generateTransferPath(fileWithSlashes, remoteDir)
        // Redundant empty segments are stripped, producing clean target path
        assertEquals("D:\\Target\\folderA\\folderB\\file.dat", transferPath)
    }

    @Test
    fun testAppendWithMultipleLeadingSlashes() {
        val base = QuickShareDirectory("/sdcard/Download/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        val appended = base.append("////sub1///sub2")
        assertEquals("/sdcard/Download/sub1///sub2/", appended.path)
    }

    // ==========================================
    // 5. EMPTY STRINGS & WHITESPACE TESTS
    // ==========================================

    @Test
    fun testEmptyAndWhitespacePaths() {
        val dEmptyUnix = QuickShareDirectory("", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/", dEmptyUnix.path)

        val dEmptyWin = QuickShareDirectory("", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("/", dEmptyWin.path)

        val dSpacesUnix = QuickShareDirectory("   ", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("   /", dSpacesUnix.path)

        val dSpacesWin = QuickShareDirectory("   ", QuickShareDirectory.FILE_SYSTEM_WINDOWS)
        assertEquals("   \\", dSpacesWin.path)
    }

    @Test
    fun testAppendEmptyOrSlashOnly() {
        val base = QuickShareDirectory("/data/", QuickShareDirectory.FILE_SYSTEM_UNIX)
        assertEquals("/data/", base.append("").path)
        assertEquals("/data/", base.append("/").path)
        assertEquals("/data/", base.append("///").path)
        assertEquals("/data/", base.append("\\\\\\").path)
    }

    // ==========================================
    // 6. TRAFFIC INFO & BIG DATA EDGE CASES
    // ==========================================

    @Test
    fun testTrafficInfoHugeByteCounts() {
        val traffic = TrafficInfo("wlan0", uploadTraffic = 0L, downloadTraffic = 0L)
        val oneTerabyte = 1024L * 1024L * 1024L * 1024L // 1 TB
        val onePetabyte = oneTerabyte * 1024L // 1 PB

        traffic.addUpload(onePetabyte)
        traffic.addDownload(2 * onePetabyte)

        assertEquals(onePetabyte, traffic.uploadTraffic)
        assertEquals(2 * onePetabyte, traffic.downloadTraffic)
        assertEquals(3 * onePetabyte, traffic.totalTraffic())
    }

    @Test
    fun testTrafficInfoNearLongMaxValue() {
        val halfMax = Long.MAX_VALUE / 2
        val traffic = TrafficInfo("eth0", uploadTraffic = halfMax, downloadTraffic = halfMax)

        // totalTraffic does not overflow Long.MAX_VALUE
        val total = traffic.totalTraffic()
        assertEquals(halfMax * 2, total)
        assertTrue(total > 0L)
    }

    @Test
    fun testSpeedAndEtaEdgeCasesZeroElapsedTime() {
        // Zero or negative speeds / remaining bytes should not throw ArithmeticException
        assertEquals(0L, TrafficInfoTest.TestTrafficInfo.calculateEtaSeconds(0L, 0L))
        assertEquals(0L, TrafficInfoTest.TestTrafficInfo.calculateEtaSeconds(1000L, 0L))
        assertEquals(0L, TrafficInfoTest.TestTrafficInfo.calculateEtaSeconds(-500L, 1000L))
        assertEquals(0L, TrafficInfoTest.TestTrafficInfo.calculateEtaSeconds(1000L, -100L))

        // Terabyte and Petabyte formatting
        val terabytes = 5L * 1024L * 1024L * 1024L * 1024L
        val formattedSize = TrafficInfoTest.TestTrafficInfo.formatSize(terabytes)
        assertTrue(formattedSize.contains("GB") || formattedSize.contains("5120.00 GB"))

        val terabyteSpeed = 2L * 1024L * 1024L * 1024L * 1024L
        val formattedSpeed = TrafficInfoTest.TestTrafficInfo.formatSpeed(terabyteSpeed)
        assertTrue(formattedSpeed.contains("GB/s") || formattedSpeed.contains("2048.00 GB/s"))
    }
}
