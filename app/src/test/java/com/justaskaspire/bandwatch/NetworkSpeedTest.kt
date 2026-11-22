package com.justaskaspire.bandwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedTest {
    @Test fun combinesDownloadAndUploadOverElapsedTime() {
        assertEquals(1_312_000L, NetworkSpeed.combinedBytesPerSecond(1_280_000L, 32_000L, 1_000L))
    }
    @Test fun handlesLongerSamplingIntervals() {
        assertEquals(2_048L, NetworkSpeed.combinedBytesPerSecond(8_192L, 2_048L, 5_000L))
    }
    @Test fun ignoresNegativeCounterDeltas() {
        assertEquals(1_024L, NetworkSpeed.combinedBytesPerSecond(-500L, 1_024L, 1_000L))
    }
    @Test fun returnsZeroForInvalidElapsedTime() {
        assertEquals(0L, NetworkSpeed.combinedBytesPerSecond(100L, 100L, 0L))
    }
    @Test fun formatsZeroAndKilobytesPerSecond() {
        assertEquals("0 KB/s", NetworkSpeed.format(0L))
        assertEquals("1.0 KB/s", NetworkSpeed.format(1_024L))
        assertEquals("10 KB/s", NetworkSpeed.format(10_240L))
    }
    @Test fun formatsMegabytesPerSecond() {
        assertEquals("1.25 MB/s", NetworkSpeed.format(1_310_720L))
    }
    @Test fun statusBarLabelUsesCompactNumericValue() {
        assertEquals("0", NetworkSpeed.statusBarLabel(0L))
        assertEquals("<1", NetworkSpeed.statusBarLabel(500L))
        assertEquals("1", NetworkSpeed.statusBarLabel(1_024L))
        assertEquals("850", NetworkSpeed.statusBarLabel(870_400L))
        assertEquals("1.2", NetworkSpeed.statusBarLabel(1_258_291L))
        assertEquals("12", NetworkSpeed.statusBarLabel(12L * 1024L * 1024L))
        assertEquals("—", NetworkSpeed.statusBarLabel(null))
    }
    @Test fun statusBarUnitIsShownSeparately() {
        assertEquals("KB/S", NetworkSpeed.statusBarUnit(0L))
        assertEquals("KB/S", NetworkSpeed.statusBarUnit(870_400L))
        assertEquals("MB/S", NetworkSpeed.statusBarUnit(1_258_291L))
        assertEquals("GB/S", NetworkSpeed.statusBarUnit(1024L * 1024L * 1024L))
        assertEquals("KB/S", NetworkSpeed.statusBarUnit(null))
    }
    @Test fun formatsUnsupportedCounter() {
        assertEquals("— KB/s", NetworkSpeed.format(-1L))
    }
    @Test fun detectsBand3AndBand7FromEarfcn() {
        assertEquals("Band 3", LteBand.fromEarfcn(1200))
        assertEquals("Band 3", LteBand.fromEarfcn(1949))
        assertEquals("Band 7", LteBand.fromEarfcn(2750))
        assertEquals("Band 7", LteBand.fromEarfcn(3449))
        assertEquals("LTE band unknown", LteBand.fromEarfcn(2000))
    }
}
