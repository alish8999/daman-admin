package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SystemdServerOpsStatsParseTest {

    private static final String MEMINFO = "MemTotal:        3906292 kB\nMemFree:          212340 kB\n"
            + "MemAvailable:    1596532 kB\nBuffers:           10000 kB\n";

    @Test void meminfoIsConvertedFromKbToBytesUsingAvailableNotFree() {
        long[] r = SystemdServerOps.parseMeminfo(MEMINFO);
        assertThat(r).containsExactly(3906292L * 1024, 1596532L * 1024);
    }

    @Test void meminfoMissingMemAvailableIsUnknownNotZero() {
        assertThat(SystemdServerOps.parseMeminfo("MemTotal: 3906292 kB\nMemFree: 1 kB\n")).isNull();
    }

    @Test void meminfoGarbageOrImpossibleIsUnknown() {
        assertThat(SystemdServerOps.parseMeminfo("")).isNull();
        assertThat(SystemdServerOps.parseMeminfo("MemTotal: lots kB\nMemAvailable: 5 kB")).isNull();
        assertThat(SystemdServerOps.parseMeminfo("MemTotal: 100 kB\nMemAvailable: 200 kB")).isNull();
        assertThat(SystemdServerOps.parseMeminfo("MemTotal: 0 kB\nMemAvailable: 0 kB")).isNull();
    }

    @Test void firstFieldOfLoadavgAndUptime() {
        assertThat(SystemdServerOps.parseFirstNumber("0.01 0.12 0.21 1/234 5678\n")).isEqualTo(0.01);
        assertThat(SystemdServerOps.parseFirstNumber("3687582.70 7000000.11\n")).isEqualTo(3687582.70);
    }

    @Test void unparseableFirstFieldIsNull() {
        assertThat(SystemdServerOps.parseFirstNumber("")).isNull();
        assertThat(SystemdServerOps.parseFirstNumber("abc 1")).isNull();
        assertThat(SystemdServerOps.parseFirstNumber("NaN")).isNull();
        assertThat(SystemdServerOps.parseFirstNumber("-1 2")).isNull();
        assertThat(SystemdServerOps.parseFirstNumber(null)).isNull();
    }

    @Test void memoryCurrentParsesTheTrailingNewline() {
        assertThat(SystemdServerOps.parseNonNegativeLong("682250240\n")).isEqualTo(Optional.of(682250240L));
    }

    @Test void memoryCurrentGarbageIsEmpty() {
        assertThat(SystemdServerOps.parseNonNegativeLong("max\n")).isEmpty();
        assertThat(SystemdServerOps.parseNonNegativeLong("")).isEmpty();
        assertThat(SystemdServerOps.parseNonNegativeLong("-5")).isEmpty();
        assertThat(SystemdServerOps.parseNonNegativeLong(null)).isEmpty();
    }
}
