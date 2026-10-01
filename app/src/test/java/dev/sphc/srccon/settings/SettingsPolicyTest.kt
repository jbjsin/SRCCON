package dev.sphc.srccon.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPolicyTest {
    @Test fun defaultsAndCorruptDurationsStayWithinSafeBounds() {
        assertEquals(600 to 30, SettingsPolicy.normalizedDurations(null, null))
        assertEquals(1 to 1, SettingsPolicy.normalizedDurations(-100, 999))
        assertEquals(86_400 to 1, SettingsPolicy.normalizedDurations(Int.MAX_VALUE, Int.MIN_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMaximumIsRejected() {
        SettingsPolicy.requireMaximumExposure(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun singleExposureOverMaximumIsRejected() {
        SettingsPolicy.requireSingleExposure(31, 30)
    }
}
