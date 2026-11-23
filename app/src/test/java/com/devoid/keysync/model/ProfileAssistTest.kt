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
        tapSequence234Enabled = true,
        tapSequence234GapMinMs = 20,
        tapSequence234GapMaxMs = 30,
    )

    @Test fun assistRoundTripKeepsLayoutAndSwitches() {
        val profile = sampleProfile()
        val draft = profile.assist().copy(
            quickSwapEnabled = false,
            tapSequence234GapMinMs = 40,
            tapSequence234GapMaxMs = 60,
        )

        val saved = profile.withAssist(draft)

        assertEquals(profile.id, saved.id)
        assertEquals(profile.name, saved.name)
        assertEquals(profile.layoutScreenWidth, saved.layoutScreenWidth)
        assertEquals(profile.layoutScreenHeight, saved.layoutScreenHeight)
        assertTrue(!saved.quickSwapEnabled)
        assertEquals(40, saved.tapSequence234GapMinMs)
        assertEquals(60, saved.tapSequence234GapMaxMs)
        // 再取一次草稿必须和刚写回的一致，isDirty 才能归零。
        assertEquals(draft, saved.assist())
    }

    @Test fun withAssistClampsTapGapToSupportedRange() {
        val profile = sampleProfile()
        val tooFast = profile.withAssist(
            profile.assist().copy(tapSequence234GapMinMs = 1, tapSequence234GapMaxMs = 1)
        )
        val tooSlow = profile.withAssist(
            profile.assist().copy(tapSequence234GapMinMs = 9999, tapSequence234GapMaxMs = 9999)
        )

        assertEquals(5, tooFast.tapSequence234GapMinMs)
        assertEquals(5, tooFast.tapSequence234GapMaxMs)
        assertEquals(500, tooSlow.tapSequence234GapMinMs)
        assertEquals(500, tooSlow.tapSequence234GapMaxMs)
    }

    @Test fun withAssistKeepsGapMaxAtLeastMin() {
        val profile = sampleProfile()
        val saved = profile.withAssist(
            profile.assist().copy(tapSequence234GapMinMs = 80, tapSequence234GapMaxMs = 20)
        )

        assertEquals(80, saved.tapSequence234GapMinMs)
        assertEquals(80, saved.tapSequence234GapMaxMs)
    }
}
