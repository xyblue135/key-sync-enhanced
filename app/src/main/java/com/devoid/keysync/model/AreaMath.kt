package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import kotlin.random.Random

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

    /** 多边形是否包含该点（射线法，边界视为内部）。 */
    fun contains(points: List<Offset>, p: Offset): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 把点限制在多边形内：内部原样返回，外部投影到最近的一条边上。
     * 顶点少于 3 个时原样返回。
     */
    fun clampTo(points: List<Offset>, p: Offset): Offset {
        if (points.size < 3) return p
        if (contains(points, p)) return p
        var best = p
        var bestDist = Float.MAX_VALUE
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val lenSq = abx * abx + aby * aby
            val t = if (lenSq < 1e-6f) 0f else {
                (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
            }
            val q = Offset(a.x + abx * t, a.y + aby * t)
            val dist = (q - p).getDistanceSquared()
            if (dist < bestDist) {
                bestDist = dist
                best = q
            }
            j = i
        }
        return best
    }

    /** 多边形的外接半径（质心到最远顶点的距离），用于回中阈值等按「半宽」估算的地方。 */
    fun radius(points: List<Offset>, center: Offset = centroid(points)): Float {
        var maxSq = 0f
        points.forEach {
            val d = (it - center).getDistanceSquared()
            if (d > maxSq) maxSq = d
        }
        return kotlin.math.sqrt(maxSq)
    }

    /**
     * 在多边形内部均匀随机取一个点。
     *
     * 以第一个顶点为扇心把多边形拆成三角形，按面积加权挑一个三角形，
     * 再用 sqrt 变换得到三角形内的均匀分布。顶点过少时退回质心。
     */
    fun randomPoint(points: List<Offset>, random: Random = Random.Default): Offset {
        if (points.size < 3) return centroid(points)
        val origin = points[0]
        val areas = FloatArray(points.size - 2)
        var total = 0f
        for (i in 1 until points.lastIndex) {
            val a = triangleArea(origin, points[i], points[i + 1])
            areas[i - 1] = a
            total += a
        }
        if (total <= 1e-4f) return centroid(points)
        var pick = random.nextFloat() * total
        var index = 0
        while (index < areas.lastIndex && pick > areas[index]) {
            pick -= areas[index]
            index++
        }
        val b = points[index + 1]
        val c = points[index + 2]
        var u = random.nextFloat()
        var v = random.nextFloat()
        if (u + v > 1f) {
            u = 1f - u
            v = 1f - v
        }
        return origin + Offset(b.x - origin.x, b.y - origin.y) * u +
            Offset(c.x - origin.x, c.y - origin.y) * v
    }

    private fun triangleArea(a: Offset, b: Offset, c: Offset): Float =
        kotlin.math.abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f
}
