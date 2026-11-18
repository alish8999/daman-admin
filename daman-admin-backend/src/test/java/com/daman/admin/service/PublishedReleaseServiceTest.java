package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The newest version that actually EXISTS for download, read from the update feed the shops
 * themselves poll.
 *
 * <p>Why this had to exist: the portal's "which shops are behind" column measured against
 * {@code app_versions}, and that table is authoritative on the PC that runs the build, not on
 * the server — the live copy stopped in September with three rows, newest 1.0.3. Every shop
 * running 1.3.7 or later therefore had nothing newer to be behind, and the whole fleet was
 * painted green. The fleet's own highest reported version was the first fix, but that is a
 * LAGGING signal: it can only ever say what some shop already installed, so on the day a
 * release ships and nobody has taken it yet, it reports the release before it.
 *
 * <p>{@code latest.yml} is the real answer. It is what electron-updater fetches, so by
 * definition it names the version a shop would get if it updated right now.
 */
class PublishedReleaseServiceTest {

    private static final String SAMPLE = """
            version: 1.5.5
            files:
              - url: Daman-Accounting_1.5.5_generic.exe
                sha512: Pi4Ni+JCb5bow2wx3lMzLDacabqOuwLvdd6fDOEUoHHkO6WkNW9iOqRg==
                size: 208585348
            path: Daman-Accounting_1.5.5_generic.exe
            sha512: Pi4Ni+JCb5bow2wx3lMzLDacabqOuwLvdd6fDOEUoHHkO6WkNW9iOqRg==
            releaseDate: '2026-11-17T21:11:22.372Z'

            releaseNotes: |
              [EN]
              - Extras you buy now switch themselves on.
            """;

    @Test
    void parsesTheVersionFromARealFeed() {
        assertThat(PublishedReleaseService.parseVersion(SAMPLE)).isEqualTo("1.5.5");
    }

    /**
     * Only the top-level {@code version:} key counts. The file also carries a filename, a path
     * and release notes that all contain the number, and the notes are free text a human wrote —
     * a looser search would eventually pick a version out of an English sentence.
     */
    @Test
    void ignoresVersionLikeTextElsewhereInTheFile() {
        String misleading = """
            releaseNotes: |
              version: 9.9.9 was never released
            version: 1.4.2
            """;
        assertThat(PublishedReleaseService.parseVersion(misleading)).isEqualTo("1.4.2");
    }

    @Test
    void returnsNullRatherThanGuessingWhenTheFeedIsUnusable() {
        assertThat(PublishedReleaseService.parseVersion(null)).isNull();
        assertThat(PublishedReleaseService.parseVersion("")).isNull();
        assertThat(PublishedReleaseService.parseVersion("<html>404 Not Found</html>")).isNull();
        assertThat(PublishedReleaseService.parseVersion("version:\n")).isNull();
    }

    /** Both Windows feeds are read; 64-bit and 32-bit can ship a release apart. */
    @Test
    void takesTheHighestAcrossFeeds() {
        assertThat(PublishedReleaseService.highest(List.of("1.5.5", "1.5.4"))).isEqualTo("1.5.5");
        assertThat(PublishedReleaseService.highest(List.of("1.4.2", "1.5.0"))).isEqualTo("1.5.0");
    }

    /**
     * Part-by-part numeric compare, never string compare: "1.5.10" is NEWER than "1.5.9",
     * which lexicographic ordering gets backwards — and the patch number has already passed 9
     * once, at 1.3.10.
     */
    @Test
    void comparesNumericallyNotAlphabetically() {
        assertThat(PublishedReleaseService.highest(List.of("1.5.9", "1.5.10"))).isEqualTo("1.5.10");
        assertThat(PublishedReleaseService.highest(List.of("1.3.9", "1.3.10"))).isEqualTo("1.3.10");
    }

    @Test
    void ignoresFeedsThatCouldNotBeRead() {
        assertThat(PublishedReleaseService.highest(java.util.Arrays.asList(null, "1.5.5", null)))
                .isEqualTo("1.5.5");
        assertThat(PublishedReleaseService.highest(java.util.Arrays.asList(null, null))).isNull();
        assertThat(PublishedReleaseService.highest(List.of())).isNull();
    }
}
