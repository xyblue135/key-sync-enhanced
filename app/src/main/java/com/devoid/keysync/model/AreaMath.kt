package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset

/** 自定义区域几何计算（纯函数，方便单测）。 */
object AreaMath {
    /**
     * 多边形的面积质心。
     *
     * 顶点过少或面积退化（三点共线、重复点）时退回平均点，避免质心落到原点。
     */
    fun centroid(points: List<Offset>): Offset {
        if (points.isEmpty()) return Offset.Zero
        if (points.size < 3) return average(points)
        var twiceArea = 0f
        var cx = 0f
        var cy = 0f
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            val cross = current.x * next.y - next.x * current.y
            twiceArea += cross
            cx += (current.x + next.x) * cross
            cy += (current.y + next.y) * cross
        }
        if (kotlin.math.abs(twiceArea) < 1e-3f) return average(points)
        val factor = 1f / (3f * twiceArea)
        return Offset(cx * factor, cy * factor)
    }

    private fun average(points: List<Offset>): Offset {
        var x = 0f
        var y = 0f
        points.forEach {
            x += it.x
            y += it.y
        }
        return Offset(x / points.size, y / points.size)
    }
}
