package com.daman.admin.service;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.repository.AppVersionRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Locks the "generic" branch of {@link BuildService} — the local equivalent of the
 * generic-installer CI workflow. Mirrors {@code BuildServicePosBranchTest}: private
 * helpers are exercised directly via reflection because the full {@code executeBuild}
 * pipeline (real Maven / electron-builder) can't run in a unit test.
 */
class BuildServiceGenericBranchTest {

    @TempDir
    Path tempDir;

    private BuildService buildService;
    private ClientConfigRepository clientConfigRepository;

    /** Drops submitted tasks so startBuild()'s async executeBuild() never runs
     *  (and never mutates the returned status) inside a unit test. */
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
        clientConfigRepository = mock(ClientConfigRepository.class);
        buildService = new BuildService(
                clientConfigRepository,
                mock(ClientConfigService.class),
                mock(BuildLogRepository.class),
                mock(LicenseKeyService.class),
                mock(AppVersionRepository.class));
        ReflectionTestUtils.setField(buildService, "workspacePath", tempDir.toString());
        ReflectionTestUtils.setField(buildService, "buildExecutor", new NoopExecutor());
    }

    private Object invokePrivate(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = BuildService.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(buildService, args);
    }

    @Test
    void writeGenericConfig_writesNeutralConfigAndGenericMeta_toBothTrees() throws Exception {
        Path backendRoot = tempDir.resolve("daman-backend");
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Path backendResources = backendRoot.resolve("src/main/resources");
        Path frontendAssets = frontendRoot.resolve("src/assets");
        Files.createDirectories(backendResources);
        Files.createDirectories(frontendAssets);

        String knownJson = "{\"appName\":\"Daman\",\"marker\":\"generic-test-config\"}";
        Files.writeString(frontendAssets.resolve("client.config.generic.json"), knownJson);
        Files.writeString(backendResources.resolve("client-meta.generic.properties"), "client.generic=true\n");

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("generic")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        Object dto = invokePrivate("writeGenericConfig",
                new Class<?>[]{Path.class, Path.class, String.class, BuildStatusDto.class},
                backendRoot, frontendRoot, "1.2.3", status);

        assertThat(Files.readString(backendResources.resolve("client.config.json"))).isEqualTo(knownJson);
        assertThat(Files.readString(frontendAssets.resolve("client.config.json"))).isEqualTo(knownJson);

        String meta = Files.readString(backendResources.resolve("client-meta.properties"));
        assertThat(meta).contains("client.generic=true");
        assertThat(meta).contains("client.version=1.2.3");
        assertThat(meta).doesNotContain("client.code=");

        assertThat(((ClientConfigExportDto) dto).getAppName()).isEqualTo("Daman");
    }

    @Test
    void writeGenericConfig_missingGenericFiles_throwsIOException() throws Exception {
        Path backendRoot = tempDir.resolve("daman-backend");
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Files.createDirectories(backendRoot.resolve("src/main/resources"));
        Files.createDirectories(frontendRoot.resolve("src/assets"));
        // Neither client.config.generic.json nor client-meta.generic.properties seeded.

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("generic")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        assertThatThrownBy(() -> invokePrivate("writeGenericConfig",
                new Class<?>[]{Path.class, Path.class, String.class, BuildStatusDto.class},
                backendRoot, frontendRoot, "1.0.0", status))
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void startBuild_forGeneric_doesNotRequireAClientConfigRow() {
        BuildStatusDto status = buildService.startBuild(BuildService.GENERIC, "win", "1.0.0");

        assertThat(status.getStatus()).isEqualTo("BUILDING");
        assertThat(status.getClientCode()).isEqualTo("generic");
        verify(clientConfigRepository, never()).findByClientCode(anyString());
    }

    @Test
    void relocateArtifact_forGeneric_usesGenericIdentifier() throws Exception {
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Path distDir = frontendRoot.resolve("dist-electron");
        Files.createDirectories(distDir);
        Path rawInstaller = distDir.resolve("Daman Setup 1.0.0.exe");
        Files.writeString(rawInstaller, "fake");

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("generic")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        String result = (String) invokePrivate("relocateArtifact",
                new Class<?>[]{String.class, String.class, String.class, String.class, Path.class, String.class, BuildStatusDto.class},
                rawInstaller.toString(), "generic", "Daman", "1.0.0", tempDir, "win", status);

        Path resultPath = Path.of(result);
        assertThat(resultPath.getFileName().toString()).isEqualTo("Daman_1.0.0_generic.exe");
        assertThat(resultPath.getParent()).isEqualTo(tempDir.resolve("clients-build").resolve("generic"));
    }
}
