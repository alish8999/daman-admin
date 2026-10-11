package com.daman.admin.service;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.entity.AppVersion;
import com.daman.admin.repository.AppVersionRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks {@code writeReleaseNotesAsset} — the bundled copy of a version's
 * changelog that backs the shipped app's Settings → What's New.
 *
 * The hazard worth a test: the file is written into the SHARED workspace and
 * survives between builds, so a path that silently skips the write ships the
 * previous client's or version's notes. Same shape as the sibling BuildService
 * tests — the private helper is driven by reflection, because the real
 * executeBuild() pipeline runs Maven and electron-builder.
 */
class BuildServiceReleaseNotesAssetTest {

    @TempDir
    Path tempDir;

    private BuildService buildService;
    private AppVersionRepository appVersionRepository;

    private static class NoopExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { /* intentionally dropped */ }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return true; }
        @Override public boolean isTerminated() { return true; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @BeforeEach
    void setUp() {
        appVersionRepository = mock(AppVersionRepository.class);
        buildService = new BuildService(
                mock(ClientConfigRepository.class),
                mock(ClientConfigService.class),
                mock(BuildLogRepository.class),
                mock(LicenseKeyService.class),
                appVersionRepository);
        ReflectionTestUtils.setField(buildService, "workspacePath", tempDir.toString());
        ReflectionTestUtils.setField(buildService, "buildExecutor", new NoopExecutor());
    }

    private String runWriter(String versionNumber) throws Exception {
        Path frontendRoot = tempDir.resolve("daman-frontend");
        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("acme")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();
        Method m = BuildService.class.getDeclaredMethod(
                "writeReleaseNotesAsset", Path.class, String.class, BuildStatusDto.class);
        m.setAccessible(true);
        m.invoke(buildService, frontendRoot, versionNumber, status);
        return Files.readString(frontendRoot.resolve("src/assets/release-notes.json"));
    }

    private static AppVersion version(String number, String changelog) {
        AppVersion v = new AppVersion();
        v.setVersionNumber(number);
        v.setReleaseDate(LocalDate.of(2026, 10, 10));
        v.setChangelogText(changelog);
        return v;
    }

    @Test
    void writesVersionDateAndRawBilingualText() throws Exception {
        when(appVersionRepository.findByVersionNumber("1.4.2"))
                .thenReturn(Optional.of(version("1.4.2", "[EN]\n- Faster receipts\n\n[AR]\n- فواتير أسرع")));

        String json = runWriter("1.4.2");

        assertThat(json).contains("\"version\" : \"1.4.2\"");
        assertThat(json).contains("\"releaseDate\" : \"2026-10-10\"");
        // Raw and unsplit: the app does the language split (release-notes-core.js).
        assertThat(json).contains("[EN]").contains("[AR]").contains("Faster receipts");
    }

    /** A textarea-edited changelog can arrive with CRLF; the app splits on \n. */
    @Test
    void normalisesWindowsLineEndings() throws Exception {
        when(appVersionRepository.findByVersionNumber("1.4.2"))
                .thenReturn(Optional.of(version("1.4.2", "[EN]\r\n- One\r\n\r\n[AR]\r\n- واحد")));

        assertThat(runWriter("1.4.2")).doesNotContain("\\r\\n");
    }

    /**
     * THE point of the whole test class. A version with no changelog must still
     * overwrite the file — the previous build's notes are sitting there.
     */
    @Test
    void overwritesAPreviousBuildsNotesWhenThisVersionHasNone() throws Exception {
        Path dest = tempDir.resolve("daman-frontend/src/assets/release-notes.json");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "{\"version\":\"1.4.1\",\"changelogText\":\"[EN]\\n- Old news\"}");

        when(appVersionRepository.findByVersionNumber("1.4.2"))
                .thenReturn(Optional.of(version("1.4.2", null)));

        String json = runWriter("1.4.2");

        assertThat(json).doesNotContain("Old news").doesNotContain("1.4.1");
        assertThat(json).contains("\"version\" : \"1.4.2\"");
        assertThat(json).contains("\"changelogText\" : \"\"");
    }

    /** No row for this version at all — still neutral, never the last build's. */
    @Test
    void writesNeutralFileWhenNoVersionRowExists() throws Exception {
        Path dest = tempDir.resolve("daman-frontend/src/assets/release-notes.json");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "{\"version\":\"1.4.1\",\"changelogText\":\"[EN]\\n- Old news\"}");

        when(appVersionRepository.findByVersionNumber(anyString())).thenReturn(Optional.empty());

        String json = runWriter("1.4.2");

        assertThat(json).doesNotContain("Old news");
        assertThat(json).contains("\"changelogText\" : \"\"");
        // The requested version is still recorded, so the page can name it.
        assertThat(json).contains("\"version\" : \"1.4.2\"");
    }

    /** A repository blowing up must not fail the build, nor leak stale notes. */
    @Test
    void writesNeutralFileWhenTheLookupThrows() throws Exception {
        Path dest = tempDir.resolve("daman-frontend/src/assets/release-notes.json");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "{\"version\":\"1.4.1\",\"changelogText\":\"[EN]\\n- Old news\"}");

        when(appVersionRepository.findByVersionNumber(anyString()))
                .thenThrow(new RuntimeException("db down"));

        String json = runWriter("1.4.2");

        assertThat(json).doesNotContain("Old news");
        assertThat(json).contains("\"changelogText\" : \"\"");
    }

    /** No version passed: falls back to the newest release, as writeChangelog does. */
    @Test
    void fallsBackToTheLatestVersionWhenNoneRequested() throws Exception {
        when(appVersionRepository.findTopByOrderByReleaseDateDesc())
                .thenReturn(Optional.of(version("9.9.9", "[EN]\n- Newest\n\n[AR]\n- الأحدث")));

        String json = runWriter(null);

        assertThat(json).contains("\"version\" : \"9.9.9\"").contains("Newest");
    }

    /** The assets directory does not exist yet on a clean workspace. */
    @Test
    void createsTheAssetsDirectoryWhenMissing() throws Exception {
        when(appVersionRepository.findByVersionNumber("1.0.0"))
                .thenReturn(Optional.of(version("1.0.0", "[EN]\n- First\n\n[AR]\n- الأول")));

        assertThat(runWriter("1.0.0")).contains("First");
        assertThat(tempDir.resolve("daman-frontend/src/assets/release-notes.json")).exists();
    }
}
