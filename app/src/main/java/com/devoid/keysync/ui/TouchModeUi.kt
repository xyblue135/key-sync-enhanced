package com.devoid.keysync.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.devoid.keysync.model.TouchMode

/**
 * 触摸模式固定配色：点击=绿、按住=蓝、轮盘=紫。
 * null 表示「跟随全局」，用次要文字色，避免和某个具体模式混淆。
 */
@Composable
fun touchModeColor(mode: TouchMode?): Color = when (mode) {
    TouchMode.TAP -> Color(0xFF4CAF50)
    TouchMode.HOLD -> Color(0xFF2196F3)
    TouchMode.WHEEL -> Color(0xFF9C27B0)
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 下拉与单选里的短名称。 */
fun touchModeShortName(mode: TouchMode): String = when (mode) {
    TouchMode.TAP -> "点击"
    TouchMode.HOLD -> "按住"
    TouchMode.WHEEL -> "轮盘"
}
