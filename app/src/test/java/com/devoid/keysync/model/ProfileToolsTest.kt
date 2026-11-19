package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ProfileToolsTest {
    private fun source() = Profile(
        id = "a",
        name = "步兵",
        items = listOf(
            DraggableItem.VariableKey(1, Offset(10f, 20f), 52, 150),
            DraggableItem.WASDGroup(2, Offset(30f, 40f)),
        ),
        swapPairs = listOf(
            SwapPair(aItemId = 1, bItemId = 2, swapOnKeyCode = 52, swapOffKeyCode = 53),
        ),
    )

    @Test
    fun swapPairKeepsLegacySingleKeyAndSupportsMultipleOnKeys() {
        // 旧预设只有单键字段，读出来仍然生效。
        assertEquals(listOf(52), SwapPair(1, 2, 52, 53).onKeys)
        // 新预设用多键：1234 任意一个都能触发同一对交换。
        assertEquals(
            listOf(8, 9, 10, 11),
            SwapPair(1, 2, 0, 53, swapOnKeyCodes = listOf(8, 9, 10, 11)).onKeys
        )
    }

    @Test
    fun editingCopiedLayoutCannotMutateOriginal() {
        val original = source()
        val copied = original.independentCopy("b", "载具")
        copied.items[0].position = Offset(300f, 400f)
        (copied.items[1] as DraggableItem.WASDGroup).scale = 1.4f
        assertEquals(Offset(10f, 20f), original.items[0].position)
        assertEquals(1f, (original.items[1] as DraggableItem.WASDGroup).scale, 0f)
        assertNotSame(original.items[0], copied.items[0])
        assertEquals(original.swapPairs, copied.swapPairs)
    }

    @Test
    fun copyGetsFreshIdAndName() {
        val original = source()
        val copied = original.independentCopy("b", "载具")
        assertEquals("b", copied.id)
        assertEquals("载具", copied.name)
        assertEquals("a", original.id)
        assertEquals("步兵", original.name)
    }

    @Test
    fun wholeSetClipboardRemapsProfileIds() {
        val first = source()
        val second = first.independentCopy("b", "载具")
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ProfileBundle>(
            json.encodeToString(ProfileBundle(profiles = listOf(first, second))),
        )
        assertEquals(1, decoded.version)
        var n = 0
        val pasted = importProfileCopies(decoded.profiles, emptyList()) { "new-${++n}" }
        assertEquals(listOf("new-1", "new-2"), pasted.map { it.id })
        assertEquals(listOf("步兵", "载具"), pasted.map { it.name })
    }

    @Test
    fun pasteRenamesWhenTheNameIsAlreadyTaken() {
        val first = source()
        val pasted = importProfileCopies(listOf(first), listOf(first)) { "new" }.single()
        assertEquals("步兵 副本 1", pasted.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySourceListIsRejected() {
        importProfileCopies(emptyList(), emptyList()) { "x" }
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateSourceIdsAreRejected() {
        val first = source()
        importProfileCopies(listOf(first, first), emptyList()) { "x" }
    }
}