package com.daman.admin.service;

import com.daman.admin.entity.AppVersion;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.repository.AppVersionRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A generic build must not start without a release note.
 *
 * Reported 2026-11-04: Settings → What's New was always empty in the shipped app. Nothing was
 * broken — `writeReleaseNotesAsset` had written `changelogText: ""` because the AppVersion row for
 * the version being built had no changelog text. The build logged it
 * ("(no changelog text)") and carried on.
 *
 * The same field feeds TWO things a client sees, which is why an empty one is worth refusing a
 * build over rather than warning about: the bundled asset behind Settings → What's New after the
 * update, and `latest.yml`'s releaseNotes, which electron-updater hands over with the
 * update-available event BEFORE the update. Shipping without it means a client can neither see
 * what is coming nor what they just got.
 *
 * Scoped to the GENERIC build on purpose: that is the one installer that reaches clients now
 * (per-client builds are retired), and failing a per-client build on a field that build does not
 * surface would be a surprise with no upside.
 */
class BuildServiceRequiresReleaseNotesTest {

    @TempDir
    Path tempDir;

    private BuildService buildService;
    private AppVersionRepository appVersionRepository;
    private ClientConfigRepository clientConfigRepository;

    private static class NoopExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { /* never actually builds */ }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return true; }
        @Override public boolean isTerminated() { return true; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @BeforeEach
    void setUp() {
        appVersionRepository = mock(AppVersionRepository.class);
        clientConfigRepository = mock(ClientConfigRepository.class);
        buildService = new BuildService(
                clientConfigRepository,
                mock(ClientConfigService.class),
                mock(BuildLogRepository.class),
                mock(LicenseKeyService.class),
                appVersionRepository);
        ReflectionTestUtils.setField(buildService, "workspacePath", tempDir.toString());
        ReflectionTestUtils.setField(buildService, "buildExecutor", new NoopExecutor());
    }

    private static AppVersion version(String number, String changelog) {
        AppVersion v = new AppVersion();
        v.setVersionNumber(number);
        v.setReleaseDate(LocalDate.of(2026, 11, 3));
        v.setChangelogText(changelog);
        return v;
    }

    private void genericBuild(String version) {
        buildService.startBuild(BuildService.GENERIC, "win", version);
    }

    // ── Refused ───────────────────────────────────────────────────────────────

    @Test
    void refusesAGenericBuildWhenTheChangelogIsEmpty() {
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", "")));
        assertThatThrownBy(() -> genericBuild("1.3.4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release note");
    }

    @Test
    void refusesAGenericBuildWhenTheChangelogIsNull() {
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", null)));
        assertThatThrownBy(() -> genericBuild("1.3.4")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAGenericBuildWhenTheChangelogIsOnlyWhitespace() {
        // A textarea the owner tabbed through leaves "\n" or " " behind, which writes as blank
        // into the asset and reads as empty in the app — so it must fail like an empty one.
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", "  \n\r\n ")));
        assertThatThrownBy(() -> genericBuild("1.3.4")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAGenericBuildWhenNoVersionRowExistsAtAll() {
        when(appVersionRepository.findByVersionNumber("9.9.9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> genericBuild("9.9.9")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMessageSaysWhichVersionAndWhatToDo() {
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", "")));
        assertThatThrownBy(() -> genericBuild("1.3.4"))
                .hasMessageContaining("1.3.4");
    }

    // ── Allowed ───────────────────────────────────────────────────────────────

    @Test
    void allowsAGenericBuildWhenTheChangelogHasText() {
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", "[EN]\n- Faster receipts\n\n[AR]\n- فواتير أسرع")));
        assertThatCode(() -> genericBuild("1.3.4")).doesNotThrowAnyException();
    }

    @Test
    void aBlankVersionFallsBackToTheLatestRowTheAssetWriterWouldUse() {
        // writeReleaseNotesAsset() resolves a blank version with findTopByOrderByReleaseDateDesc().
        // The gate must resolve it the SAME way, or it would pass on one row and then ship another.
        when(appVersionRepository.findTopByOrderByReleaseDateDesc())
                .thenReturn(Optional.of(version("1.3.5", "[EN]\n- Something real")));
        assertThatCode(() -> genericBuild("")).doesNotThrowAnyException();
    }

    @Test
    void aBlankVersionIsStillRefusedWhenTheLatestRowHasNoText() {
        when(appVersionRepository.findTopByOrderByReleaseDateDesc())
                .thenReturn(Optional.of(version("1.3.5", "")));
        assertThatThrownBy(() -> genericBuild("")).isInstanceOf(IllegalStateException.class);
    }

    // ── Unchanged for per-client builds ───────────────────────────────────────

    @Test
    void doesNotGateAPerClientBuildOnTheChangelog() {
        // Per-client builds are retired and do not surface this field. Failing them on it would be
        // a surprise with no upside, so the gate is deliberately generic-only.
        when(clientConfigRepository.findByClientCode("acme"))
                .thenReturn(Optional.of(new ClientConfig()));
        when(appVersionRepository.findByVersionNumber("1.3.4"))
                .thenReturn(Optional.of(version("1.3.4", "")));
        assertThatCode(() -> buildService.startBuild("acme", "win", "1.3.4"))
                .doesNotThrowAnyException();
    }
}
