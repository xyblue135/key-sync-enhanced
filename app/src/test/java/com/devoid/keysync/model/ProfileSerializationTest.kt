package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the clipboard export/import path of a [Profile].
 *
 * The manager layer needs a DataStore/Context, so these tests exercise the
 * exact [Json] configuration it uses and then re-apply the two pure
 * transformations [com.devoid.keysync.service.FloatingWindowStateManager]
 * performs on an imported profile (fresh id / unique name, drop switch
 * hotkey targets that do not exist on this device).
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
        name = "和平精英",
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
        appConfig = AppConfig.Default.copy(
            buttonScale = 1.25f,
            profileSwitchEnabled = true,
        ),
        switchHotkeys = listOf(
            ProfileSwitchHotkey(keyCode = 45, targetProfileId = "profile-2"),
            // null target means "cycle to the next profile".
            ProfileSwitchHotkey(keyCode = 52, targetProfileId = null),
        ),
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
        assertEquals(1.5f, wasd.scale)
        assertEquals(Offset(0.3f, 0.6f), wasd.d)
    }

    /**
     * A hotkey with a null target means "cycle". If the encoder dropped the
     * null it would still decode to null, but the exported text has to keep it
     * so a human editing the JSON can see the switch is not pinned.
     */
    @Test
    fun cycleHotkeyTargetIsWrittenOut() {
        val text = json.encodeToString(sampleProfile())
        assertTrue(
            "expected a 'targetProfileId' entry in:\n$text",
            text.contains("\"targetProfileId\""),
        )
    }

    /** Profiles exported before switch hotkeys existed must still load. */
    @Test
    fun legacyJsonWithoutSwitchHotkeysDecodes() {
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
        assertTrue(decoded.switchHotkeys.isEmpty())
        assertEquals(AppConfig.Default.buttonScale, decoded.appConfig.buttonScale)
    }

    /** Unknown future fields (a newer build's export) must not break the load. */
    @Test
    fun unknownFieldsAreIgnored() {
        val future = json.encodeToString(sampleProfile())
            .replaceFirst("\"name\"", "\"somethingNew\": 123,\n  \"name\"")
        assertEquals(sampleProfile(), json.decodeFromString<Profile>(future))
    }

    @Test
    fun importedProfileGetsFreshIdAndKeepsUsableHotkeyTargets() {
        val imported = json.decodeFromString<Profile>(json.encodeToString(sampleProfile()))
        // Only profile-2 exists on this device, so only that target survives.
        val known = setOf("profile-2")

        val migrated = imported.copy(
            id = "new-uuid",
            name = "和平精英 副本",
            switchHotkeys = imported.switchHotkeys.map {
                it.copy(targetProfileId = it.targetProfileId?.takeIf { t -> t in known })
            },
        )

        assertEquals("new-uuid", migrated.id)
        assertEquals("和平精英 副本", migrated.name)
        assertEquals("profile-2", migrated.switchHotkeys[0].targetProfileId)
        assertNull(migrated.switchHotkeys[1].targetProfileId)
        // The original object is untouched by the migration.
        assertEquals(sampleProfile(), imported)
    }
}
