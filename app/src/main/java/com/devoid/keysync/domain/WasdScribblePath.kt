package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * WASD 按住期间的「乱线」轨迹生成。
 *
 * 参考真人拇指推摇杆时的真实轨迹：方向连续变化、速度时快时慢、偶尔绕圈回穿，
 * 而不是每次都在同一个像素点上来回抖。做法是先在意图方向附近撒一圈随机控制点，
 * 再用 Catmull-Rom 样条串成一条闭合路径，最后重采样成固定数量的点按 60Hz 播放。
 *
 * 纯函数，便于单测；不持有任何状态，每条路径由调用方缓存。
 */
object WasdScribblePath {
    /** 采样间隔：60Hz，接近普通手机的触控采样密度。 */
    const val STEP_MS = 16L

    /** 一条路径默认覆盖约 19 秒（1200 × 16ms），超过后由调用方重新生成。 */
    const val DEFAULT_SAMPLES = 1200

    /** 摇杆死区：低于这个半径游戏会认为没有推动。 */
    private const val MIN_RADIUS_RATIO = 0.30f

    /** 摇杆有效外圈：不贴到最边缘，留一点厚度。 */
    private const val MAX_RADIUS_RATIO = 0.98f

    /** 控制点数量：越多路径越碎，28 个已经足够看不出重复。 */
    private const val CONTROL_POINTS = 28

    /**
     * 生成一条围绕 [intent] 的乱线路径。
     *
     * @param center 摇杆中心（屏幕坐标）
     * @param intent 当前方向的目标点（按住时的基准落点）
     * @param joystickRadius 摇杆外圈半径（像素）
     * @param amplitude 乱线幅度（像素）；0 时退化为固定在 [intent]
     * @param samples 重采样点数
     */
    fun generate(
        center: Offset,
        intent: Offset,
        joystickRadius: Float,
        amplitude: Float,
        samples: Int = DEFAULT_SAMPLES,
        random: Random = Random.Default,
    ): List<Offset> {
        val joystickR = joystickRadius.coerceAtLeast(1f)
        val count = samples.coerceIn(8, 4000)
        if (amplitude <= 0.5f) return List(count) { intent }

        // 1. 在意图点周围撒控制点。用 sqrt 让点在圆盘内均匀分布，
        //    再让约 15% 的控制点走得更远，形成偶尔的大回环。
        val controls = ArrayList<Offset>(CONTROL_POINTS)
        repeat(CONTROL_POINTS) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val reach = if (random.nextFloat() < 0.15f) {
                0.85f + random.nextFloat() * 0.35f
            } else {
                0.25f + sqrt(random.nextFloat()) * 0.75f
            }
            val distance = amplitude * reach
            controls += Offset(
                intent.x + cos(angle) * distance,
                intent.y + sin(angle) * distance,
            )
        }

        // 2. Catmull-Rom 闭合插值：首尾相接，路径播放完可以无缝接回开头。
        val result = ArrayList<Offset>(count)
        val stepPerControl = count / CONTROL_POINTS
        val remainder = count % CONTROL_POINTS
        for (i in controls.indices) {
            val p0 = controls[(i - 1 + controls.size) % controls.size]
            val p1 = controls[i]
            val p2 = controls[(i + 1) % controls.size]
            val p3 = controls[(i + 2) % controls.size]
            val segmentSteps = stepPerControl + if (i < remainder) 1 else 0
            for (s in 0 until segmentSteps) {
                val t = s.toFloat() / segmentSteps
                result += catmullRom(p0, p1, p2, p3, t)
            }
        }

        // 3. 限制在摇杆有效范围内：外圈不贴边，内圈不进退区。
        val minRadius = joystickR * MIN_RADIUS_RATIO
        val maxRadius = joystickR * MAX_RADIUS_RATIO
        return List(count) { index ->
            clampToAnnulus(result[index % result.size], center, minRadius, maxRadius)
        }
    }

    /** 标准 Catmull-Rom 样条：曲线经过 p1、p2，p0/p3 只决定切线。 */
    private fun catmullRom(a: Offset, b: Offset, c: Offset, d: Offset, t: Float): Offset {
        val t2 = t * t
        val t3 = t2 * t
        val x = 0.5f * (
            2f * b.x +
                (-a.x + c.x) * t +
                (2f * a.x - 5f * b.x + 4f * c.x - d.x) * t2 +
                (-a.x + 3f * b.x - 3f * c.x + d.x) * t3
            )
        val y = 0.5f * (
            2f * b.y +
                (-a.y + c.y) * t +
                (2f * a.y - 5f * b.y + 4f * c.y - d.y) * t2 +
                (-a.y + 3f * b.y - 3f * c.y + d.y) * t3
            )
        return Offset(x, y)
    }

    private fun clampToAnnulus(
        point: Offset,
        center: Offset,
        minRadius: Float,
        maxRadius: Float,
    ): Offset {
        val delta = point - center
        val length = delta.getDistance()
        if (length < 0.001f) {
            return center + Offset(minRadius, 0f)
        }
        val clamped = length.coerceIn(minRadius, maxRadius)
        return center + delta * (clamped / length)
    }
}
