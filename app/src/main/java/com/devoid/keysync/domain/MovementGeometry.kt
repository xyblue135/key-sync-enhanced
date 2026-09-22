package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset

internal fun sprintOffset(forward: Int, side: Int, forwardDistance: Float, sideDistance: Float): Offset =
    Offset(side * sideDistance, -forward * forwardDistance)

internal fun withinWheel(delta: Offset, radius: Float): Offset {
    if (!delta.x.isFinite() || !delta.y.isFinite()) return Offset.Zero
    val length = delta.getDistance()
    return if (length > radius && length > 0f) delta * (radius / length) else delta
}
