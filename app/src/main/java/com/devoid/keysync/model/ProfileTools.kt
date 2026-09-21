package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileBundle(val version: Int = 1, val profiles: List<Profile>)

/** Layout items are mutable; a shallow Profile.copy would link both layouts. */
fun Profile.independentCopy(newId: String, newName: String): Profile = copy(
    id = newId,
    name = newName,
    items = items.map { source ->
        source.copy().also {
            it.touchCenter = source.touchCenter
            it.cancelTouchCenter = source.cancelTouchCenter
        }
    },
    switchHotkeys = switchHotkeys.map {
        it.copy(targetProfileId = if (it.targetProfileId == id) newId else it.targetProfileId)
    },
    // Preserve the key in copied data, but do not steal the original's shortcut.
    activationHotkeyEnabled = false,
)

fun profileActivationRoutes(profiles: List<Profile>): List<ProfileSwitchHotkey> = profiles
    .filter { it.activationHotkeyEnabled && it.activationKeyCode != null }
    .distinctBy { it.activationKeyCode }
    .map { ProfileSwitchHotkey(it.activationKeyCode!!, it.id) }

/** Remap cross-profile references when pasting a whole set on any device. */
fun importProfileCopies(sources: List<Profile>, existing: List<Profile>, newId: () -> String): List<Profile> {
    require(sources.isNotEmpty()) { "预设列表为空" }
    require(sources.map { it.id }.distinct().size == sources.size) { "预设 ID 重复" }
    val ids = sources.associate { it.id to newId() }
    val known = existing.mapTo(mutableSetOf()) { it.id }
    val occupied = existing.filter { it.activationHotkeyEnabled }.mapNotNullTo(mutableSetOf()) { it.activationKeyCode }
    val names = existing.mapTo(mutableSetOf()) { it.name }
    return sources.map { source ->
        var name = source.name
        var suffix = 1
        while (name in names) { name = "${source.name} 副本 ${suffix++}" }
        names.add(name)
        val enabled = source.activationHotkeyEnabled &&
            (source.activationKeyCode == null || occupied.add(source.activationKeyCode))
        source.independentCopy(ids.getValue(source.id), name).copy(
            activationHotkeyEnabled = enabled,
            switchHotkeys = source.switchHotkeys.mapNotNull { hotkey ->
                val target = hotkey.targetProfileId
                when {
                    target == null -> hotkey
                    target in ids -> hotkey.copy(targetProfileId = ids.getValue(target))
                    target in known -> hotkey
                    else -> null // Missing target must not become an accidental cycle shortcut.
                }
            }
        )
    }
}
