package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileBundle(val version: Int = 1, val profiles: List<Profile>)

/**
 * Swap on [swapOnKeyCode], restore on [swapOffKeyCode].
 * Moves layout position only, never the key binding / name.
 */
@Serializable
data class SwapPair(
    val aItemId: Int,
    val bItemId: Int,
    val swapOnKeyCode: Int,
    val swapOffKeyCode: Int,
)

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
