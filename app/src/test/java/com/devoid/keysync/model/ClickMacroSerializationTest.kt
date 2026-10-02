package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.domain.KEYCODE_MOUSE_BACK
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ClickMacroSerializationTest {
    private val macro = ClickMacro("m", "Two clicks", KEYCODE_MOUSE_BACK,
        listOf(MacroStep("a", 0.25f, 0.75f, 350), MacroStep("b", 0.6f, 0.4f)), true)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun oldProfileWithoutMacrosLoadsWithEmptyList() {
        assertTrue(json.decodeFromString<Profile>("""{"id":"old","name":"Old"}""").macros.isEmpty())
    }

    @Test fun profileExportAndImportPreserveIndependentPointsAndIntervals() {
        val profile = Profile("p", "Preset", macros = listOf(macro))
        val decoded = json.decodeFromString<Profile>(json.encodeToString(profile))
        assertEquals(profile, decoded)
        assertEquals(listOf(macro), importProfileCopies(listOf(decoded), emptyList()) { "copy" }.single().macros)
    }

    @Test fun changingCopiedMacroDoesNotAffectOriginal() {
        val source = Profile("p", "Original", macros = listOf(macro))
        val copy = source.independentCopy("copy", "Copy")
        val edited = copy.copy(macros = copy.macros.map { it.copy(steps = it.steps.map { s -> s.copy(x = 0.9f) }) })
        assertEquals(0.25f, source.macros.first().steps.first().x, 0f)
        assertEquals(0.9f, edited.macros.first().steps.first().x, 0f)
    }

    @Test fun duplicateEnabledTriggersRejectedButDisabledAlternativeAllowed() {
        val other = macro.copy(id = "other")
        assertNotNull(validateClickMacros(listOf(macro, other)))
        assertNull(validateClickMacros(listOf(macro, other.copy(enabled = false))))
    }

    @Test fun importRejectsInvalidCoordinatesOrTiming() {
        listOf(MacroStep("bad", x = -1f), MacroStep("bad", y = Float.POSITIVE_INFINITY),
            MacroStep("bad", delayAfterMs = -1), MacroStep("bad", delayAfterMs = 5001)).forEach { invalid ->
            val profile = Profile("p", "Bad", macros = listOf(macro.copy(steps = listOf(invalid))))
            try {
                importProfileCopies(listOf(profile), emptyList()) { "copy" }
                fail("Invalid macro must not be imported")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun pointProjectionUsesViewportOriginAndClampsRightBottomEdge() {
        assertEquals(Offset(110f, 220f), MacroStep("point", 0.5f, 0.5f)
            .screenPosition(Offset(200f, 400f), Offset(10f, 20f)))
        assertEquals(Offset(209f, 419f), MacroStep("point", 1f, 1f)
            .screenPosition(Offset(200f, 400f), Offset(10f, 20f)))
    }
}
