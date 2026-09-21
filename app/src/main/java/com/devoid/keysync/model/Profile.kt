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
    /**
     * Keys that switch away from *this* profile when released. Kept on the
     * source profile so "pressing X while preset 1 is active goes to preset 2"
     * is expressible without a global key table.
     */
    val switchHotkeys: List<ProfileSwitchHotkey> = emptyList(),
    /** Press/release this key from any layout to activate this profile. */
    val activationKeyCode: Int? = null,
    val activationHotkeyEnabled: Boolean = true,
)
