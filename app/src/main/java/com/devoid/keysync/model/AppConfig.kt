package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val buttonScale: Float,
    val deleteDataOnRemove: Boolean = Default.deleteDataOnRemove,
    val cancellableTouchMode: TouchMode = Default.cancellableTouchMode,
    val normalBtnTouchMode: TouchMode = Default.normalBtnTouchMode,
    // 鼠标指针灵敏度（0.1..1.0），随预设保存：切换预设时自动应用。
    val pointerSensitivity: Float = Default.pointerSensitivity,
) {
    companion object {
        val Default = AppConfig(
            buttonScale = 1f,
            deleteDataOnRemove = false,
            cancellableTouchMode = TouchMode.TAP,
            normalBtnTouchMode = TouchMode.TAP,
            pointerSensitivity = 0.5f,
        )
    }
}
