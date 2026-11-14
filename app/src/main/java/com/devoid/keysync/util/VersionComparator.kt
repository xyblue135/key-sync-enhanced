package com.devoid.keysync.util

/**
 * 比较版本标签，用于「检查更新」。
 *
 * 版本号形如 `1.48-android16`、`v1.49-android16`、`1.50`，debug 包会多一个 `-debug` 后缀。
 * 只比较版本号核心（开头的点分数字部分），因此 `-android16` 之类后缀里的数字
 * 不会被当成更高的版本段；核心完全相同时认为没有更新。
 */
object VersionComparator {

    fun normalize(raw: String): String = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .removeSuffix("-debug")
        .lowercase()

    /** @return true 当 [remote] 的版本号核心高于 [current]。 */
    fun isNewer(current: String, remote: String): Boolean {
        val left = segments(normalize(current))
        val right = segments(normalize(remote))
        if (left.isEmpty() || right.isEmpty()) return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0L }
            val b = right.getOrElse(index) { 0L }
            if (a != b) return b > a
        }
        return false
    }

    private fun segments(version: String): List<Long> {
        val core = Regex("^\\d+(?:\\.\\d+)*").find(version)?.value ?: return emptyList()
        return core.split('.').mapNotNull { it.toLongOrNull() }
    }
}
