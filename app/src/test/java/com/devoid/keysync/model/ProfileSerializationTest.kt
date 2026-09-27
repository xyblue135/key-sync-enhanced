package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the clipboard export/import path of a [Profile].
 *
 * The manager layer needs a DataStore/Context, so these tests exercise the exact
 * [Json] configuration it uses plus the pure transformations in ProfileTools.
 */
class ProfileSerializationTest {

    /** Must stay in sync with FloatingWindowStateManager.profileJson. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun sampleProfile() = Profile(
        id = "profile-1",
        name = "示例预设",
        items = listOf(
            DraggableItem.WASDGroup(
                id = 1,
                position = Offset(0.2f, 0.6f),
                scale = 1.5f,
                center = Offset(0.2f, 0.6f),
                w = Offset(0.2f, 0.5f),
                a = Offset(0.1f, 0.6f),
                s = Offset(0.2f, 0.7f),
                d = Offset(0.3f, 0.6f),
            ),
            DraggableItem.FixedKey(
                id = 2,
                position = Offset(0.8f, 0.7f),
                type = DraggableItemType.FIRE,
                keyCode = 45,
                size = 60,
            ),
            DraggableItem.CancelableKey(
                id = 3,
                position = Offset(0.9f, 0.3f),
                cancelPosition = Offset(0.75f, 0.3f),
                type = DraggableItemType.SCOPE,
                keyCode = 52,
                size = 55,
            ),
            DraggableItem.VariableKey(
                id = 4,
                position = Offset(0.5f, 0.2f),
                keyCode = 62,
                size = 50,
            ),
        ),
        appConfig = AppConfig.Default.copy(buttonScale = 1.25f),
        swapPairs = listOf(
            SwapPair(aItemId = 2, bItemId = 3, swapOnKeyCode = 45, swapOffKeyCode = 52),
        ),
        layoutScreenWidth = 2400,
        layoutScreenHeight = 1080,
    )

    @Test
    fun roundTripPreservesEveryField() {
        val source = sampleProfile()
        val decoded = json.decodeFromString<Profile>(json.encodeToString(source))
        assertEquals(source, decoded)
    }

    /** The polymorphic sealed hierarchy must survive with its subtype intact. */
    @Test
    fun roundTripKeepsItemSubtypes() {
        val decoded = json.decodeFromString<Profile>(json.encodeToString(sampleProfile()))
        assertEquals(
            listOf(
                DraggableItem.WASDGroup::class,
                DraggableItem.FixedKey::class,
                DraggableItem.CancelableKey::class,
                DraggableItem.VariableKey::class,
            ),
            decoded.items.map { it::class },
        )
        val wasd = decoded.items.first() as DraggableItem.WASDGroup
        assertEquals(1.5f, wasd.scale, 0f)
        assertEquals(Offset(0.3f, 0.6f), wasd.d)
    }

    /** Transient runtime coordinates must never reach the clipboard text. */
    @Test
    fun transientMeasuredPositionsAreNotSerialized() {
        val profile = sampleProfile()
        profile.items.forEach { it.touchCenter = Offset(1234f, 5678f) }
        val text = json.encodeToString(profile)
        assertFalse("touchCenter leaked into the export", text.contains("touchCenter"))
        assertNull(json.decodeFromString<Profile>(text).items.first().touchCenter)
    }

    /**
     * The layout baseline is what lets a layout survive a resolution or
     * orientation change; losing it silently rescales every button.
     */
    @Test
    fun layoutBaselineSurvivesRoundTrip() {
        val decoded = json.decodeFromString<Profile>(json.encodeToString(sampleProfile()))
        assertEquals(2400, decoded.layoutScreenWidth)
        assertEquals(1080, decoded.layoutScreenHeight)
    }

    /** Profiles written before swap pairs and baselines existed must still load. */
    @Test
    fun legacyJsonWithoutSwapPairsDecodes() {
        val legacy = """
            {
              "id": "old",
              "name": "旧版预设",
              "items": [
                {
                  "type": "com.devoid.keysync.model.DraggableItem.VariableKey",
                  "id": 1,
                  "position": "0.5,0.5",
                  "keyCode": 62,
                  "size": 50
                }
              ],
              "appConfig": { "buttonScale": 1.0 }
            }
        """.trimIndent()
        val decoded = json.decodeFromString<Profile>(legacy)
        assertEquals(1, decoded.items.size)
        assertTrue(decoded.swapPairs.isEmpty())
        assertEquals(0, decoded.layoutScreenWidth)
        assertEquals(AppConfig.Default.buttonScale, decoded.appConfig.buttonScale, 0f)
    }

    /** Unknown future fields (a newer build's export) must not break the load. */
    @Test
    fun unknownFieldsAreIgnored() {
        val future = json.encodeToString(sampleProfile())
            .replaceFirst("\"name\"", "\"somethingNew\": 123,\n  \"name\"")
        assertEquals(sampleProfile(), json.decodeFromString<Profile>(future))
    }
}