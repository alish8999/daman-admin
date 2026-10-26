package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SystemdTimestampParseTest {

    private static final Optional<Instant> EXPECTED = Optional.of(Instant.parse("2026-10-26T14:56:41Z"));

    @Test void parsesTheRealBoxString() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 UTC")).isEqualTo(EXPECTED);
    }

    @Test void blankIsEmpty() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("")).isEmpty();
        assertThat(SystemdServerOps.parseSystemdTimestamp("  \n")).isEmpty();
        assertThat(SystemdServerOps.parseSystemdTimestamp(null)).isEmpty();
    }

    @Test void garbageIsEmpty() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("n/a")).isEmpty();
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-13-45 99:99:99 UTC")).isEmpty();
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 Not/AZone")).isEmpty();
    }

    @Test void numericOffsetsParse() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 17:56:41 +03")).isEqualTo(EXPECTED);
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 17:56:41 +03:00")).isEqualTo(EXPECTED);
    }

    @Test void wrongDayOfWeekStillParsesTheDate() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("Fri 2026-10-26 14:56:41 UTC")).isEqualTo(EXPECTED);
    }

    @Test void nonEnglishDefaultLocaleDoesNotMatter() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SY"));
            assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 UTC")).isEqualTo(EXPECTED);
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test void aMergedWarningLineDoesNotHideTheTimestamp() {
        assertThat(SystemdServerOps.parseSystemdTimestamp(
                "Warning: unit file changed on disk\nMon 2026-10-26 14:56:41 UTC")).isEqualTo(EXPECTED);
    }

    @Test void ambiguousZoneAbbreviationIsRejectedRatherThanGuessed() {
        // ZoneId.SHORT_IDS maps BST to Asia/Dhaka (+6), CST to Chicago, IST to Kolkata: a confidently
        // wrong instant (hours out) is worse than "unknown", which the caller surfaces visibly.
        for (String zone : new String[] {"BST", "CST", "IST", "EET", "EST"}) {
            assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 " + zone))
                    .as(zone).isEmpty();
        }
    }

    @Test void fullIanaZoneIdParses() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 Europe/London"))
                .isEqualTo(EXPECTED); // London is on GMT on 26 Oct 2026 (BST ended 25 Oct)
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 15:56:41 Europe/Berlin"))
                .isEqualTo(EXPECTED);
    }

    @Test void gmtZNumericNoColonOffsetsParse() {
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 GMT")).isEqualTo(EXPECTED);
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 14:56:41 Z")).isEqualTo(EXPECTED);
        assertThat(SystemdServerOps.parseSystemdTimestamp("Mon 2026-10-26 09:56:41 -0500")).isEqualTo(EXPECTED);
    }
}
