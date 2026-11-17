package com.daman.admin.repository;

import com.daman.admin.entity.AppVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "The latest version" has to mean one row, not whichever the database happens to
 * hand back first.
 *
 * release_date is a DATE, and several releases land on one day routinely — 1.4.4,
 * 1.5.0 and 1.5.1 were all written on 2026-11-15. Ordering by that column alone
 * leaves their order undefined, and three callers read element zero as the answer:
 * the Build card's "latest: X" label (and the +1 it suggests), the guard that
 * refuses an older version, and BuildService's release-note lookup when a build is
 * started with no version at all. On 2026-11-17 the card read "latest: 1.4.4" and
 * suggested 1.4.5 with 1.5.1 sitting in the table.
 *
 * The tie-break is the id, which is the insertion order: among releases dated the
 * same day, the one written last is the latest.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:appversionorder;DB_CLOSE_DELAY=-1",
        "daman.cloudflare.account-id=",
        "daman.cloudflare.zone-id=",
        "daman.cloudflare.tunnel-id="
})
class AppVersionOrderingTest {

    @Autowired
    AppVersionRepository repository;

    // One in-memory database for the whole class, and version_number is unique: without this,
    // the second test re-inserting 1.4.4 fails on a constraint rather than on the ordering.
    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private AppVersion write(String versionNumber, LocalDate releaseDate) {
        AppVersion v = new AppVersion();
        v.setVersionNumber(versionNumber);
        v.setReleaseDate(releaseDate);
        v.setChangelogText("[EN]\nnotes for " + versionNumber);
        return repository.saveAndFlush(v);
    }

    @Test
    void amongReleasesDatedTheSameDayTheOneWrittenLastIsTheLatest() {
        LocalDate sameDay = LocalDate.of(2026, 11, 15);
        write("1.4.4", sameDay);
        write("1.5.0", sameDay);
        write("1.5.1", sameDay);
        write("1.4.3", sameDay.minusDays(1));

        List<AppVersion> all = repository.findAllByOrderByReleaseDateDescIdDesc();

        assertThat(all).extracting(AppVersion::getVersionNumber)
                .containsExactly("1.5.1", "1.5.0", "1.4.4", "1.4.3");
    }

    @Test
    void theNewestRowIsTheOneAReleaseNoteLookupGetsWithNoVersionGiven() {
        LocalDate sameDay = LocalDate.of(2026, 11, 15);
        write("1.4.4", sameDay);
        write("1.5.1", sameDay);

        Optional<AppVersion> top = repository.findTopByOrderByReleaseDateDescIdDesc();

        // Picking 1.4.4 here would stamp the wrong release notes into a build and into
        // latest.yml, which is what the client's update badge shows.
        assertThat(top).isPresent();
        assertThat(top.get().getVersionNumber()).isEqualTo("1.5.1");
    }

    @Test
    void anOlderDateNeverOutranksANewerOneWhateverTheIds() {
        write("2.0.0", LocalDate.of(2026, 11, 16));
        write("1.0.1", LocalDate.of(2026, 11, 10));

        assertThat(repository.findTopByOrderByReleaseDateDescIdDesc())
                .get()
                .extracting(AppVersion::getVersionNumber)
                .isEqualTo("2.0.0");
    }
}
