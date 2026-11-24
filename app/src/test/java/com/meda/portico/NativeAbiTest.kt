package com.meda.portico

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Pins the native-library ABIs the APK packages: arm64-v8a only (docs/development/gocore-and-apk.md, "APK size and native
 * ABIs"). `app/build.gradle.kts` makes `testDebugUnitTest` depend on `packageDebug` and passes the
 * built APK as `portico.debugApk`; the test lists its `lib/<abi>/` entries, i.e. what really ships
 * after `ndk.abiFilters`. Without the filter the WebRTC library adds armeabi-v7a, x86 and x86_64
 * (about 35 MB compressed).
 */
class NativeAbiTest {

    private val entries: List<String> by lazy {
        val path = System.getProperty("portico.debugApk")
        assertNotNull(path, "portico.debugApk is not set: run through Gradle's testDebugUnitTest")
        val file = File(path!!)
        assertTrue(file.isFile, "debug APK missing at $path")
        ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.toList() }
    }

    @Test
    fun `only arm64-v8a native code is packaged`() {
        val abis = entries.map { it.split('/')[1] }.toSortedSet().toList()
        assertEquals(listOf("arm64-v8a"), abis, "lib entries: $entries")
    }

    @Test
    fun `arm64-v8a really carries the WebRTC library`() {
        assertTrue("lib/arm64-v8a/libjingle_peerconnection_so.so" in entries, "lib entries: $entries")
    }
}
