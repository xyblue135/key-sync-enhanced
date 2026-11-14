package com.devoid.keysync.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」只按版本号里的数字段判断，避免把 `-android16` / `-debug`
 * 这类后缀误判成新版本，也避免标签带不带 `v` 影响结果。
 */
class VersionComparatorTest {

    @Test
    fun higherNumberIsNewer() {
        assertTrue(VersionComparator.isNewer("1.48-android16", "v1.49-android16"))
        assertTrue(VersionComparator.isNewer("1.48-android16", "1.50"))
        assertTrue(VersionComparator.isNewer("1.9", "1.10"))
        assertTrue(VersionComparator.isNewer("1.48-android16-debug", "v1.49-android16"))
    }

    @Test
    fun sameNumberIsNotNewer() {
        assertFalse(VersionComparator.isNewer("1.48-android16", "v1.48-android16"))
        assertFalse(VersionComparator.isNewer("1.48-android16", "1.48"))
        assertFalse(VersionComparator.isNewer("1.48", "v1.48-android16"))
    }

    @Test
    fun lowerNumberIsNotNewer() {
        assertFalse(VersionComparator.isNewer("1.50", "v1.49-android16"))
        assertFalse(VersionComparator.isNewer("2.0", "v1.99"))
    }

    @Test
    fun malformedTagsDoNotCrash() {
        assertFalse(VersionComparator.isNewer("1.48", ""))
        assertFalse(VersionComparator.isNewer("", "v1.49"))
        assertFalse(VersionComparator.isNewer("1.48", "nightly"))
    }

    @Test
    fun normalizeStripsPrefixAndDebugSuffix() {
        assertTrue(VersionComparator.normalize("V1.48-android16-debug") == "1.48-android16")
    }
}
