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
import java.util.Collections;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildServicePosBranchTest {

    @TempDir
    Path tempDir;

    private BuildService buildService;
    private ClientConfigService clientConfigService;

    @BeforeEach
    void setUp() {
        clientConfigService = mock(ClientConfigService.class);
        buildService = new BuildService(
                mock(ClientConfigRepository.class),
                clientConfigService,
                mock(BuildLogRepository.class),
                mock(LicenseKeyService.class),
                mock(AppVersionRepository.class));
        ReflectionTestUtils.setField(buildService, "workspacePath", tempDir.toString());
    }

    /** Invokes a private method via reflection — these helpers are correctly private (no
     * public API needs them), but the plan's Global Constraints call for testing them
     * directly rather than only through the untestable full executeBuild() pipeline. */
    private Object invokePrivate(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = BuildService.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(buildService, args);
    }

    @Test
    void electronBuilderCommand_forPos_usesPosConfigAndExplicitWinFlag() throws Exception {
        String[] result = (String[]) invokePrivate("electronBuilderCommand", new Class<?>[]{String.class}, "pos");
        // shellCommand() prepends "cmd.exe", "/c" on Windows — check the tail of the array
        // regardless of OS-specific prefixing.
        String joined = String.join(" ", result);
        assertThat(joined).contains("electron-builder --config electron-builder.pos.json --win --publish never");
    }

    @Test
    void findArtifact_forPos_searchesDistElectronPosDirectory() throws Exception {
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Path posDistDir = frontendRoot.resolve("dist-electron-pos");
        Files.createDirectories(posDistDir);
        Path fakeInstaller = posDistDir.resolve("Daman POS Setup 1.0.0.exe");
        Files.writeString(fakeInstaller, "fake");

        // A decoy in the full-app's dist-electron dir must NOT be picked up for platform=pos.
        Path fullDistDir = frontendRoot.resolve("dist-electron");
        Files.createDirectories(fullDistDir);
        Files.writeString(fullDistDir.resolve("Daman Sales Setup 1.0.0.exe"), "decoy");

        String result = (String) invokePrivate("findArtifact", new Class<?>[]{Path.class, String.class}, frontendRoot, "pos");

        assertThat(result).isEqualTo(fakeInstaller.toString());
    }

    @Test
    void relocateArtifact_forPos_usesDamanPosPrefix() throws Exception {
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Path posDistDir = frontendRoot.resolve("dist-electron-pos");
        Files.createDirectories(posDistDir);
        Path rawInstaller = posDistDir.resolve("Daman POS Setup 1.0.0.exe");
        Files.writeString(rawInstaller, "fake");

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("acme")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        String result = (String) invokePrivate("relocateArtifact",
                new Class<?>[]{String.class, String.class, String.class, String.class, Path.class, String.class, BuildStatusDto.class},
                rawInstaller.toString(), "acme", "Acme POS", "1.0.0", tempDir, "pos", status);

        assertThat(Path.of(result).getFileName().toString()).isEqualTo("DamanPOS_1.0.0_acme.exe");
    }

    @Test
    void writeClientConfig_forPos_skipsBackendCopy() throws Exception {
        Path backendRoot = tempDir.resolve("daman-backend");
        Path frontendRoot = tempDir.resolve("daman-frontend");
        Files.createDirectories(backendRoot);
        Files.createDirectories(frontendRoot);

        ClientConfigExportDto exportDto = ClientConfigExportDto.builder()
                .clientCode("acme")
                .appName("Acme POS")
                .features(ClientConfigExportDto.FeaturesDto.builder().build())
                .build();
        when(clientConfigService.export("acme")).thenReturn(exportDto);

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode("acme")
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        invokePrivate("writeClientConfig",
                new Class<?>[]{String.class, Path.class, Path.class, boolean.class, BuildStatusDto.class},
                "acme", backendRoot, frontendRoot, true, status);

        assertThat(Files.exists(backendRoot.resolve("src/main/resources/client.config.json"))).isFalse();
        assertThat(Files.exists(frontendRoot.resolve("src/assets/client.config.json"))).isTrue();
    }
}
