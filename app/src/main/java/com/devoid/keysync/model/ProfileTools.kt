package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileBundle(val version: Int = 1, val profiles: List<Profile>)

/**
 * Swap on any key in [onKeys], restore on [swapOffKeyCode].
 * Moves layout position only, never the key binding / name.
 *
 * [swapOnKeyCode] is the legacy single-key field: old presets keep using it,
 * new pairs write [swapOnKeyCodes] and leave [swapOnKeyCode] as 0.
 */
@Serializable
data class SwapPair(
    val aItemId: Int,
    val bItemId: Int,
    val swapOnKeyCode: Int,
    val swapOffKeyCode: Int,
    /** 换位键集合：1234 这类多武器槽都能触发同一对交换。 */
    val swapOnKeyCodes: List<Int> = emptyList(),
) {
    /** 实际生效的换位键；新旧字段二选一，避免空集合时退化成无键可用。 */
    val onKeys: List<Int>
        get() = if (swapOnKeyCodes.isNotEmpty()) swapOnKeyCodes
        else listOfNotNull(swapOnKeyCode.takeIf { it != 0 })
}

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
)



/** Remap cross-profile references when pasting a whole set on any device. */
fun importProfileCopies(sources: List<Profile>, existing: List<Profile>, newId: () -> String): List<Profile> {
    require(sources.isNotEmpty()) { "预设列表为空" }
    require(sources.map { it.id }.distinct().size == sources.size) { "预设 ID 重复" }
    sources.forEach { profile ->
        validateClickMacros(profile.macros)?.let { throw IllegalArgumentException(it) }
    }
    val ids = sources.associate { it.id to newId() }
    val names = existing.mapTo(mutableSetOf()) { it.name }
    return sources.map { source ->
        var name = source.name
        var suffix = 1
        while (name in names) { name = "${source.name} 副本 ${suffix++}" }
        names.add(name)
        source.independentCopy(ids.getValue(source.id), name)
    }
}
