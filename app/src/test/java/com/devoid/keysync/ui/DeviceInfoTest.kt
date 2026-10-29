package com.devoid.keysync.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device card exists to answer "why did my layout move?", so the parts worth
 * testing are the derived numbers and the viewport-vs-baseline comparison -- not
 * the composable that draws them.
 */
class DeviceInfoTest {

    @Test
    fun logicalSizeDividesPixelsByDensity() {
        val info = info(widthPx = 2400, heightPx = 1080, density = 3f)
        assertEquals(800, info.widthDp)
        assertEquals(360, info.heightDp)
    }

    @Test
    fun logicalSizeFallsBackToPixelsWhenDensityIsUnknown() {
        // A zero density must not produce a divide-by-zero or a nonsense dp value.
        val info = info(widthPx = 1080, heightPx = 2400, density = 0f)
        assertEquals(1080, info.widthDp)
        assertEquals(2400, info.heightDp)
    }

    @Test
    fun diagonalIsDerivedFromPixelsAndDpi() {
        // sqrt(2400^2 + 1080^2) / 480 = 5.4829..., the same maths the vendor quotes.
        val info = info(widthPx = 2400, heightPx = 1080, densityDpi = 480)
        assertEquals("5.48", formatInches(info.diagonalInches))
    }

    @Test
    fun diagonalDoesNotDependOnOrientation() {
        val landscape = info(widthPx = 2400, heightPx = 1080)
        val portrait = info(widthPx = 1080, heightPx = 2400)
        assertEquals(landscape.diagonalInches, portrait.diagonalInches, 0.0001f)
    }

    @Test
    fun diagonalIsUnavailableWithoutADpi() {
        val info = info(densityDpi = 0)
        assertEquals(0f, info.diagonalInches, 0f)
        assertEquals("-", formatInches(info.diagonalInches))
    }

    @Test
    fun mismatchIsReportedWhenTheViewportDiffersFromTheBaseline() {
        // The 1.46 case: coordinates saved against 2608x1200, drawn in 2400x1080.
        val info = info(
            viewport = Offset(2400f, 1080f),
            baseline = Offset(2608f, 1200f),
        )
        assertTrue(info.baselineMismatch)
    }

    @Test
    fun mismatchIsReportedWhenOnlyOneAxisDiffers() {
        val info = info(
            viewport = Offset(2400f, 1200f),
            baseline = Offset(2400f, 1080f),
        )
        assertTrue(info.baselineMismatch)
    }

    @Test
    fun noMismatchWhenTheViewportAndTheBaselineAgree() {
        val info = info(
            viewport = Offset(2400f, 1080f),
            baseline = Offset(2400f, 1080f),
        )
        assertFalse(info.baselineMismatch)
    }

    @Test
    fun mismatchIsUnknownUntilBothSidesAreKnown() {
        // Before the overlay service reports a frame, or on a profile that never
        // recorded a baseline, there is nothing to compare -- and a false alarm
        // here would send the user chasing a layout bug that does not exist.
        assertFalse(info(viewport = null, baseline = Offset(2608f, 1200f)).baselineMismatch)
        assertFalse(info(viewport = Offset(2400f, 1080f), baseline = null).baselineMismatch)
        assertFalse(info(viewport = null, baseline = null).baselineMismatch)
    }

    @Test
    fun scaleIsAlwaysFormattedWithADot() {
        // Locale.US on purpose: a comma decimal separator would make the copied
        // report ambiguous next to the "2400 x 1080" pixel pair.
        assertEquals("3.0x", formatScale(3f))
        assertEquals("2.8x", formatScale(2.75f))
        assertEquals("1.0x", formatScale(1f))
    }

    @Test
    fun inchesAreRoundedToTwoDecimals() {
        assertEquals("5.48", formatInches(5.483f))
        assertEquals("6.00", formatInches(6f))
        assertEquals("-", formatInches(0f))
        assertEquals("-", formatInches(-1f))
    }

    private fun info(
        widthPx: Int = 2400,
        heightPx: Int = 1080,
        densityDpi: Int = 480,
        density: Float = 3f,
        viewport: Offset? = null,
        baseline: Offset? = null,
    ) = DeviceInfo(
        widthPx = widthPx,
        heightPx = heightPx,
        densityDpi = densityDpi,
        density = density,
        isLandscape = widthPx > heightPx,
        viewportPx = viewport,
        baselinePx = baseline,
        deviceModel = "Test Device",
        androidRelease = "15",
        sdkInt = 35,
    )
}
