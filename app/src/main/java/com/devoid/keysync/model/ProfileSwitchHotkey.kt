package com.devoid.keysync.model

import kotlinx.serialization.Serializable

/**
 * A hardware key that switches away from the profile that owns it.
 *
 * The switch is deliberately performed on the key's *release* edge: the press
 * edge stays with the regular mapping, so one physical key can both trigger
 * its mapped action and change profile without either behaviour stealing the
 * key from the other (see EventHandler.handleKeyEvent).
 *
 * @param keyCode Android [android.view.KeyEvent] key code of the physical key.
 * @param targetProfileId profile to activate, or null to cycle to the next one.
 */
@Serializable
data class ProfileSwitchHotkey(
    val keyCode: Int,
    val targetProfileId: String? = null,
)
