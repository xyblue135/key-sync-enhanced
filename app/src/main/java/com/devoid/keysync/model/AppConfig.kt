package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val buttonScale: Float,
    val deleteDataOnRemove: Boolean = Default.deleteDataOnRemove,
    val themePreference: ThemePreference = Default.themePreference,
    val cancellableTouchMode: TouchMode = Default.cancellableTouchMode,
    val normalBtnTouchMode: TouchMode = Default.normalBtnTouchMode,
    // When true, touch events received by the floating overlay (e.g. injected
    // by a screen-mirror / keymapper such as 熊猫映射) are translated into
    // mouse events so PC-mouse input can reach the target game through KeySync.
    val screenMirrorCompatMode: Boolean = Default.screenMirrorCompatMode,
    /**
     * Master switch for [ProfileSwitchHotkey]. Off by default so an
     * already-working keymap never gets a key silently repurposed as a
     * profile switch after an update.
     */
    val profileSwitchEnabled: Boolean = Default.profileSwitchEnabled,
    // 鼠标指针灵敏度（0.1..1.0），随预设保存：切换预设时自动应用。
    val pointerSensitivity: Float = Default.pointerSensitivity,
) {
    companion object {
        val Default = AppConfig(
            buttonScale = 1f,
            deleteDataOnRemove = false,
            themePreference = ThemePreference.SYSTEM_DYNAMIC,
            cancellableTouchMode = TouchMode.TAP,
            normalBtnTouchMode = TouchMode.TAP,
            screenMirrorCompatMode = false,
            profileSwitchEnabled = false,
            pointerSensitivity = 0.5f,
        )
    }
}
