package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 疾跑落点：把方向单位向量投到以 [sideDistance]（横向）和 [forwardDistance]（前向）
 * 为半轴的椭圆上，四个正方向和斜方向落在同一圈。
 *
 * 之前直接把两轴距离相加，(W+D) 会变成 (sideDistance, -forwardDistance)，离圆心
 * sqrt(a² + b²)。默认 a=b=1.5R 时约 2.1R，早已超出摇杆边缘，轨迹图上只能被裁到
 * 圆边，看起来是一段标准圆弧。
 */
internal fun sprintOffset(forward: Int, side: Int, forwardDistance: Float, sideDistance: Float): Offset {
    if (forward == 0 && side == 0) return Offset.Zero
    val length = hypot(side.toFloat(), forward.toFloat())
    val nx = side / length
    val ny = -forward / length
    val a = sideDistance.coerceAtLeast(1f)
    val b = forwardDistance.coerceAtLeast(1f)
    val radius = 1f / sqrt((nx / a) * (nx / a) + (ny / b) * (ny / b))
    return Offset(nx * radius, ny * radius)
}

internal fun withinWheel(delta: Offset, radius: Float): Offset {
    val length = delta.getDistance()
    return if (length > radius && length > 0f) delta * (radius / length) else delta
}
