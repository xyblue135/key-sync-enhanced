package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProfileToolsTest {
    private fun source() = Profile("a", "步兵", items = listOf(
        DraggableItem.VariableKey(1, Offset(10f, 20f), 52, 150),
        DraggableItem.WASDGroup(2, Offset(30f, 40f)),
    ), activationKeyCode = 52, switchHotkeys = listOf(ProfileSwitchHotkey(8, "b")))

    @Test fun editingCopiedLayoutCannotMutateOriginal() {
        val original = source()
        val copied = original.independentCopy("b", "载具")
        copied.items[0].position = Offset(300f, 400f)
        (copied.items[1] as DraggableItem.WASDGroup).scale = 1.4f
        assertEquals(Offset(10f, 20f), original.items[0].position)
        assertEquals(1f, (original.items[1] as DraggableItem.WASDGroup).scale)
        assertEquals(52, copied.activationKeyCode)
        assertFalse(copied.activationHotkeyEnabled)
    }

    @Test fun activationRoutesAreTheSameFromEitherPreset() {
        val first = source()
        val second = first.independentCopy("b", "载具").copy(activationKeyCode = 8, activationHotkeyEnabled = true)
        assertEquals(listOf(ProfileSwitchHotkey(52,"a"), ProfileSwitchHotkey(8,"b")),
            profileActivationRoutes(listOf(first, second)))
    }

    @Test fun wholeSetClipboardPreservesKeysAndRemapsLinks() {
        val first = source()
        val second = Profile("b", "载具", activationKeyCode = 8,
            switchHotkeys = listOf(ProfileSwitchHotkey(52, "a")))
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ProfileBundle>(json.encodeToString(ProfileBundle(profiles = listOf(first, second))))
        var id = 0
        val pasted = importProfileCopies(decoded.profiles, emptyList()) { "new-${++id}" }
        assertEquals(52, pasted[0].activationKeyCode)
        assertEquals(8, pasted[1].activationKeyCode)
        assertEquals(pasted[1].id, pasted[0].switchHotkeys[0].targetProfileId)
        assertEquals(pasted[0].id, pasted[1].switchHotkeys[0].targetProfileId)
    }

    @Test fun pasteKeepsExistingShortcutAndPreservesDisabledCopyMetadata() {
        val first = source()
        val pasted = importProfileCopies(listOf(first), listOf(first)) { "new" }.single()
        assertEquals(52, pasted.activationKeyCode)
        assertFalse(pasted.activationHotkeyEnabled)
        assertTrue(pasted.switchHotkeys.isEmpty()) // missing b must not turn into cycling
        assertEquals(listOf(ProfileSwitchHotkey(52, "a")), profileActivationRoutes(listOf(first, pasted)))
    }

    @Test fun legacyProfilesStillLoadWithoutActivationKeys() {
        val parsed = Json.decodeFromString<Profile>("""{"id":"old","name":"旧布局"}""")
        assertNull(parsed.activationKeyCode)
        assertTrue(profileActivationRoutes(listOf(parsed)).isEmpty())
    }
}
