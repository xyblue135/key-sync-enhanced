package com.devoid.keysync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAssistTest {

    private fun sampleProfile() = Profile(
        id = "p1",
        name = "测试",
        layoutScreenWidth = 2400,
        layoutScreenHeight = 1080,
        quickSwapEnabled = true,
        tabDebounceEnabled = true,
        stanceAssistEnabled = true,
        tapSequence234Enabled = true,
        tapSequence234GapMs = 60,
    )

    @Test fun assistRoundTripKeepsLayoutAndSwitches() {
        val profile = sampleProfile()
        val draft = profile.assist().copy(
            tabDebounceEnabled = false,
            quickSwapEnabled = false,
            tapSequence234GapMs = 120,
            stanceAssistEnabled = false,
            tabDebounceMs = 800,
        )

        val saved = profile.withAssist(draft)

        assertEquals(profile.id, saved.id)
        assertEquals(profile.name, saved.name)
        assertEquals(profile.layoutScreenWidth, saved.layoutScreenWidth)
        assertEquals(profile.layoutScreenHeight, saved.layoutScreenHeight)
        assertTrue(!saved.tabDebounceEnabled)
        assertTrue(!saved.quickSwapEnabled)
        assertEquals(120, saved.tapSequence234GapMs)
        assertTrue(!saved.stanceAssistEnabled)
        assertEquals(800, saved.tabDebounceMs)
        // 再取一次草稿必须和刚写回的一致，isDirty 才能归零。
        assertEquals(draft, saved.assist())
    }

    @Test fun withAssistClampsTabDebounceToSupportedRange() {
        val profile = sampleProfile()
        val saved = profile.withAssist(
            profile.assist().copy(tabDebounceMs = 1)
        )

        assertEquals(200, saved.tabDebounceMs)
    }

    @Test fun withAssistClampsTapGapToSupportedRange() {
        val profile = sampleProfile()
        val tooFast = profile.withAssist(profile.assist().copy(tapSequence234GapMs = 1))
        val tooSlow = profile.withAssist(profile.assist().copy(tapSequence234GapMs = 9999))

        assertEquals(20, tooFast.tapSequence234GapMs)
        assertEquals(400, tooSlow.tapSequence234GapMs)
    }
}
