package com.justaskaspire.bandwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedTest {
    @Test fun combinedRateIncludesDownloadAndUpload() {
        assertEquals(1536L, NetworkSpeed.combinedBytesPerSecond(1024L, 512L, 1000L))
    }
    @Test fun invalidElapsedTimeReturnsZero() {
        assertEquals(0L, NetworkSpeed.combinedBytesPerSecond(100L, 100L, 0L))
    }
    @Test fun statusBarLabelUsesCompactNumericValue() {
        assertEquals("0", NetworkSpeed.statusBarLabel(0L))
        assertEquals("<1", NetworkSpeed.statusBarLabel(500L))
        assertEquals("1", NetworkSpeed.statusBarLabel(1024L))
        assertEquals("850", NetworkSpeed.statusBarLabel(850L * 1024L))
        assertEquals("1.2", NetworkSpeed.statusBarLabel((1.2 * 1024 * 1024).toLong()))
        assertEquals("12", NetworkSpeed.statusBarLabel(12L * 1024L * 1024L))
    }
    @Test fun statusBarUnitIsShownSeparately() {
        assertEquals("KB/S", NetworkSpeed.statusBarUnit(0L))
        assertEquals("KB/S", NetworkSpeed.statusBarUnit(850L * 1024L))
        assertEquals("MB/S", NetworkSpeed.statusBarUnit((1.2 * 1024 * 1024).toLong()))
        assertEquals("GB/S", NetworkSpeed.statusBarUnit(1024L * 1024L * 1024L))
    }
}
