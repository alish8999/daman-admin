package com.daman.admin.service;

import com.daman.admin.repository.AppSettingRepository;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DevRunService} — the config/meta-writing half (per-client +
 * generic {@code prepareConfigForMode}, {@code devCurrent}, {@code devReset}). Plain
 * JUnit 5 + Mockito, {@code @TempDir} standing in for the workspace checkout and
 * {@code ~/.daman}. The dev-licence / machine-id / db-copy cases live in a sibling
 * test added alongside these.
 */
class DevRunServiceTest {

    @TempDir
    Path workspace;

    @TempDir
    Path damanHome;

    private ClientConfigService clientConfigService;
    private DevRunService service;

    /** Whatever {@link #seedCheckoutGenericFiles()} wrote as the neutral generic config. */
    private static final String GENERIC_CONFIG_CONTENT = "{\"appName\":\"Daman\",\"__generic_marker__\":true}";

    @BeforeEach
    void setUp() throws Exception {
        clientConfigService = mock(ClientConfigService.class);
        service = new DevRunService(
                clientConfigService,
                mock(LicenseKeyService.class),
                mock(LicenseRepository.class),
                mock(ClientConfigRepository.class),
                mock(AppSettingRepository.class));
        ReflectionTestUtils.setField(service, "workspaceRoot", workspace.toString());
        ReflectionTestUtils.setField(service, "damanHomePath", damanHome.toString());

        // The real ClientConfigService.writeCheckoutConfig() writes client.config.json
        // into both the backend resources and the frontend assets, plus
        // client-meta.properties into the backend resources. DevRunService's generic /
        // devReset paths delegate to it, so reproduce that side effect against the
        // temp checkout here.
        doAnswer(inv -> {
            String json = inv.getArgument(0);
            String meta = inv.getArgument(1);
            Path be = workspace.resolve("daman-backend/src/main/resources");
            Path fe = workspace.resolve("daman-frontend/src/assets");
            Files.createDirectories(be);
            Files.createDirectories(fe);
            Files.writeString(be.resolve("client.config.json"), json);
            Files.writeString(fe.resolve("client.config.json"), json);
            Files.writeString(be.resolve("client-meta.properties"), meta);
            return null;
        }).when(clientConfigService).writeCheckoutConfig(any(), any());
    }

    /** Writes a known generic config + generic meta into the temp checkout. */
    private void seedCheckoutGenericFiles() throws IOException {
        Path be = workspace.resolve("daman-backend/src/main/resources");
        Path fe = workspace.resolve("daman-frontend/src/assets");
        Files.createDirectories(be);
        Files.createDirectories(fe);
        Files.writeString(fe.resolve("client.config.generic.json"), GENERIC_CONFIG_CONTENT);
        Files.writeString(be.resolve("client-meta.generic.properties"), "client.generic=true\n");
    }

    @Test
    void prepareConfigForMode_generic_writesNeutralConfigAndGenericMeta_plusRuntimeProps() throws Exception {
        seedCheckoutGenericFiles();
        service.prepareConfigForMode("acme", "generic");

        String backendJson = Files.readString(workspace.resolve("daman-backend/src/main/resources/client.config.json"));
        assertThat(backendJson).isEqualTo(GENERIC_CONFIG_CONTENT);
        String meta = Files.readString(workspace.resolve("daman-backend/src/main/resources/client-meta.properties"));
        assertThat(meta).contains("client.generic=true").contains("client.version=dev").doesNotContain("client.code=");
        String rp = Files.readString(damanHome.resolve("runtime.properties"));
        assertThat(rp.trim()).isEqualTo("daman.runtime.client-code=acme");
    }

    @Test
    void prepareConfigForMode_perClient_delegatesToPrepareDevConfig() {
        service.prepareConfigForMode("acme", "per-client");
        verify(clientConfigService).prepareDevConfig("acme");
    }

    @Test
    void devCurrent_readsGenericMeta_reportsGenericModeAndCode() throws Exception {
        Files.createDirectories(workspace.resolve("daman-backend/src/main/resources"));
        Files.writeString(workspace.resolve("daman-backend/src/main/resources/client-meta.properties"),
                "client.generic=true\nclient.version=dev\n");
        Files.writeString(damanHome.resolve("runtime.properties"), "daman.runtime.client-code=acme\n");

        DevRunService.DevCurrent cur = service.devCurrent();
        assertThat(cur.mode()).isEqualTo("generic");
        assertThat(cur.clientCode()).isEqualTo("acme");
        assertThat(cur.version()).isEqualTo("dev");
    }

    @Test
    void devCurrent_readsPerClientMeta_reportsPerClientModeAndCode() throws Exception {
        Files.createDirectories(workspace.resolve("daman-backend/src/main/resources"));
        Files.writeString(workspace.resolve("daman-backend/src/main/resources/client-meta.properties"),
                "client.code=acme\nclient.version=dev\n");

        DevRunService.DevCurrent cur = service.devCurrent();
        assertThat(cur.mode()).isEqualTo("per-client");
        assertThat(cur.clientCode()).isEqualTo("acme");
    }

    @Test
    void devCurrent_noMetaFile_returnsAllNull() {
        DevRunService.DevCurrent cur = service.devCurrent();
        assertThat(cur.clientCode()).isNull();
        assertThat(cur.mode()).isNull();
    }

    @Test
    void devReset_restoresGenericConfig_deletesLicenseAndActiveClient_keepsRuntimePropsAndDbFolders() throws Exception {
        seedCheckoutGenericFiles();
        // pre-state: a per-client prepare + a licence + a db folder
        Files.writeString(workspace.resolve("daman-backend/src/main/resources/client.config.json"), "REAL-ACME");
        Files.writeString(workspace.resolve("daman-backend/src/main/resources/client-meta.properties"), "client.code=acme\n");
        Files.writeString(damanHome.resolve("license.dat"), "KEY");
        Files.writeString(damanHome.resolve("active-client.json"), "{\"clientCode\":\"acme\"}");
        Files.writeString(damanHome.resolve("runtime.properties"), "daman.runtime.client-code=acme\n");
        Files.createDirectories(damanHome.resolve("acme"));
        Files.writeString(damanHome.resolve("acme/daman_db.sqlite"), "CLIENT-DATA");

        service.devReset();

        assertThat(Files.readString(workspace.resolve("daman-backend/src/main/resources/client.config.json")))
                .isEqualTo(GENERIC_CONFIG_CONTENT);
        assertThat(Files.readString(workspace.resolve("daman-backend/src/main/resources/client-meta.properties")))
                .contains("client.generic=true");
        assertThat(Files.exists(damanHome.resolve("license.dat"))).isFalse();
        assertThat(Files.exists(damanHome.resolve("active-client.json"))).isFalse();
        assertThat(Files.exists(damanHome.resolve("runtime.properties"))).isTrue();               // untouched
        assertThat(Files.readString(damanHome.resolve("acme/daman_db.sqlite"))).isEqualTo("CLIENT-DATA"); // untouched
    }
}
