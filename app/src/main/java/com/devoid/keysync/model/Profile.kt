package com.devoid.keysync.model

import kotlinx.serialization.Serializable

/**
 * A user-defined keymap preset. A user can maintain several of these
 * (e.g. infantry and vehicle layouts for the same game) and switch between them manually from the settings
 * screen. Each profile owns its own [items] (the on-screen button layout)
 * and its own [appConfig] (touch mode, theme, ...).
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val items: List<DraggableItem> = emptyList(),
    val appConfig: AppConfig = AppConfig.Default,
    /** Swap pairs: A/B button layout positions swap on swapOn, restore on swapOff. Moves position only. */
    val swapPairs: List<SwapPair> = emptyList(),
    /** Layout 保存时的屏幕分辨率（px）。加载时若与当前屏幕不同则把坐标/尺寸等比还原；0 = 未记录。 */
    val layoutScreenWidth: Int = 0,
    val layoutScreenHeight: Int = 0,
)
