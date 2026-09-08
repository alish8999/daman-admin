package com.daman.admin.service;

import com.daman.admin.entity.AppSetting;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private LicenseKeyService licenseKeyService;
    private LicenseRepository licenseRepository;
    private ClientConfigRepository clientConfigRepository;
    private AppSettingRepository appSettingRepository;
    private DevRunService service;

    /** Whatever {@link #seedCheckoutGenericFiles()} wrote as the neutral generic config. */
    private static final String GENERIC_CONFIG_CONTENT = "{\"appName\":\"Daman\",\"__generic_marker__\":true}";

    @BeforeEach
    void setUp() throws Exception {
        clientConfigService = mock(ClientConfigService.class);
        licenseKeyService = mock(LicenseKeyService.class);
        licenseRepository = mock(LicenseRepository.class);
        clientConfigRepository = mock(ClientConfigRepository.class);
        appSettingRepository = mock(AppSettingRepository.class);
        service = new DevRunService(
                clientConfigService,
                licenseKeyService,
                licenseRepository,
                clientConfigRepository,
                appSettingRepository);
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

    // ── helpers for the dev-licence / machine-id / db-copy cases ──────────────

    /** Fresh {@link DevRunService} wired to the temp paths + shared mocks, with its
     *  {@code probeLocalMachineId()} stubbed to return {@code probeValue} (null → empty). */
    private DevRunService spyServiceWithProbe(String probeValue) {
        DevRunService real = new DevRunService(
                clientConfigService, licenseKeyService, licenseRepository,
                clientConfigRepository, appSettingRepository);
        ReflectionTestUtils.setField(real, "workspaceRoot", workspace.toString());
        ReflectionTestUtils.setField(real, "damanHomePath", damanHome.toString());
        DevRunService spy = spy(real);
        doReturn(Optional.ofNullable(probeValue)).when(spy).probeLocalMachineId();
        return spy;
    }

    private Path damanHome() {
        return damanHome;
    }

    /** Stubs {@code clientConfigRepository.findByClientCode} + {@code licenseEntitlementsFor} for {@code code}. */
    private void stubClient(String code) {
        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode(code);
        cfg.setAppName(code.toUpperCase() + " POS");
        when(clientConfigRepository.findByClientCode(code)).thenReturn(Optional.of(cfg));
        when(clientConfigService.licenseEntitlementsFor(code)).thenReturn(
                new ClientConfigService.LicenseEntitlements("USD", java.util.Map.of("barcode", true), null, null));
        // devRun() now always regenerates the dev-run key from current entitlements
        // (reuse-verbatim hid later admin-config changes); default it so tests that
        // don't care about the key value still get a non-null one written to license.dat.
        lenient().when(licenseKeyService.generateLicense(any(), any(), eq(code), isNull(), any(), any(), any(), any()))
                .thenReturn(code.toUpperCase() + ".DEVRUN.KEY");
    }

    private AppSetting appSetting(String key, String val) {
        AppSetting s = new AppSetting();
        s.setSettingKey(key);
        s.setValueJson(val);
        return s;
    }

    private License activeLicence(String code, String key) {
        License l = new License();
        l.setClientCode(code);
        l.setLicenseKey(key);
        l.setStatus("ACTIVE");
        return l;
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

    // ── prepareConfigForMode: unknown mode (coverage gap from Task 2 review) ──

    @Test
    void prepareConfigForMode_unknownMode_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.prepareConfigForMode("acme", "bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── resolveDevMachineId / setDevMachineId ────────────────────────────────

    @Test
    void resolveDevMachineId_prefersLocalStatusProbe_andSelfHealsTheStoredValue() {
        // Probe returns a value → it wins AND is written back to the AppSetting.
        service = spyServiceWithProbe("F".repeat(64));
        when(appSettingRepository.findBySettingKey(DevRunService.DEV_MACHINE_ID_KEY))
                .thenReturn(Optional.of(appSetting("dev_machine_id", "OLDVALUE0000000000")));

        assertThat(service.resolveDevMachineId()).isEqualTo("F".repeat(64));
        verify(appSettingRepository).save(argThat(s -> "F".repeat(64).equals(s.getValueJson())));
    }

    @Test
    void resolveDevMachineId_probeDown_fallsBackToStoredSetting() {
        service = spyServiceWithProbe(null);
        when(appSettingRepository.findBySettingKey(DevRunService.DEV_MACHINE_ID_KEY))
                .thenReturn(Optional.of(appSetting("dev_machine_id", "A".repeat(64))));
        assertThat(service.resolveDevMachineId()).isEqualTo("A".repeat(64));
    }

    @Test
    void resolveDevMachineId_probeDownAndNoStoredSetting_throws() {
        service = spyServiceWithProbe(null);
        when(appSettingRepository.findBySettingKey(DevRunService.DEV_MACHINE_ID_KEY))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveDevMachineId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dev machine ID unknown");
    }

    @Test
    void setDevMachineId_rejectsGarbage_acceptsHex() {
        assertThatThrownBy(() -> service.setDevMachineId("nope")).isInstanceOf(IllegalArgumentException.class);
        service.setDevMachineId("a".repeat(64));
        verify(appSettingRepository).save(argThat(s -> "a".repeat(64).equals(s.getValueJson())
                && DevRunService.DEV_MACHINE_ID_KEY.equals(s.getSettingKey())));
    }

    // ── devRun ──────────────────────────────────────────────────────────────

    @Test
    void devRun_generic_mintsDevLicence_writesLicenseDatAndActiveClient_copiesDb() throws Exception {
        seedCheckoutGenericFiles();
        Path src = Files.createTempFile("client-db", ".sqlite");
        Files.writeString(src, "THEIR-DATA");
        service = spyServiceWithProbe("B".repeat(64));
        stubClient("acme");
        when(licenseRepository.findByMachineIdAndClientCodeAndStatus("B".repeat(64), "acme", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(licenseKeyService.generateLicense(any(), any(), eq("acme"), isNull(), any(), any(), any(), any()))
                .thenReturn("NEW.V2.KEY");

        DevRunService.DevRunResult r = service.devRun("acme",
                new DevRunService.DevRunRequest("generic", src.toString()));

        assertThat(r.mode()).isEqualTo("generic");
        assertThat(r.machineId()).isEqualTo("B".repeat(64));
        assertThat(r.dbCopied()).isTrue();
        assertThat(Files.readString(damanHome().resolve("license.dat"))).isEqualTo("NEW.V2.KEY");
        assertThat(Files.readString(damanHome().resolve("active-client.json"))).contains("\"acme\"");
        assertThat(Files.readString(damanHome().resolve("acme/daman_db.sqlite"))).isEqualTo("THEIR-DATA");
        verify(licenseRepository).save(argThat(l -> "ACTIVE".equals(l.getStatus()) && "dev-run".equals(l.getLabel())));
    }

    @Test
    void devRun_existingActiveDevLicence_refreshesKeyInPlace_noNewRow_no409() throws Exception {
        seedCheckoutGenericFiles();
        service = spyServiceWithProbe("B".repeat(64));
        stubClient("acme");
        License existing = new License();
        existing.setId(77L);
        existing.setLicenseKey("STALE.V2.KEY"); existing.setStatus("ACTIVE"); existing.setClientCode("acme");
        existing.setLabel("dev-run");
        when(licenseRepository.findByMachineIdAndClientCodeAndStatus("B".repeat(64), "acme", "ACTIVE"))
                .thenReturn(Optional.of(existing));
        when(licenseKeyService.generateLicense(any(), any(), eq("acme"), isNull(), any(), any(), any(), any()))
                .thenReturn("FRESH.V2.KEY");

        DevRunService.DevRunResult r = service.devRun("acme",
                new DevRunService.DevRunRequest("per-client", null));

        // regenerated from current entitlements and written, same row updated in place
        assertThat(Files.readString(damanHome().resolve("license.dat"))).isEqualTo("FRESH.V2.KEY");
        verify(licenseRepository).save(argThat(l -> l.getId() != null && l.getId() == 77L
                && "FRESH.V2.KEY".equals(l.getLicenseKey()) && "dev-run".equals(l.getLabel())));
        assertThat(r.dbCopied()).isFalse();
        assertThat(r.dbPath()).endsWith(java.io.File.separator + "acme" + java.io.File.separator + "daman_db.sqlite");
    }

    @Test
    void devRun_dbFileMissing_returns400ish() {
        seedCheckoutGenericFiles_unchecked();
        service = spyServiceWithProbe("B".repeat(64));
        stubClient("acme");
        when(licenseRepository.findByMachineIdAndClientCodeAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(activeLicence("acme", "K")));
        assertThatThrownBy(() -> service.devRun("acme",
                new DevRunService.DevRunRequest("generic", "/no/such/file.sqlite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dbFile");
    }

    @Test
    void devRun_dbCopy_replacesExistingDbAtomically_noTmpLeftBehind() throws Exception {
        seedCheckoutGenericFiles();
        // an already-imported DB for this client — a mid-copy failure must never destroy it
        Files.createDirectories(damanHome().resolve("acme"));
        Files.writeString(damanHome().resolve("acme/daman_db.sqlite"), "OLD-DATA");
        Path src = Files.createTempFile("client-db", ".sqlite");
        Files.writeString(src, "NEW-DATA");
        service = spyServiceWithProbe("B".repeat(64));
        stubClient("acme");
        when(licenseRepository.findByMachineIdAndClientCodeAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(activeLicence("acme", "K")));

        service.devRun("acme", new DevRunService.DevRunRequest("generic", src.toString()));

        assertThat(Files.readString(damanHome().resolve("acme/daman_db.sqlite"))).isEqualTo("NEW-DATA");
        assertThat(Files.exists(damanHome().resolve("acme/daman_db.sqlite.devrun-tmp"))).isFalse();
    }

    @Test
    void devRun_thenDevReset_devCurrentReturnsAllNull() throws Exception {
        seedCheckoutGenericFiles();
        service = spyServiceWithProbe("B".repeat(64));
        stubClient("acme");
        when(licenseRepository.findByMachineIdAndClientCodeAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(activeLicence("acme", "K")));

        service.devRun("acme", new DevRunService.DevRunRequest("generic", null));
        assertThat(service.devCurrent().mode()).isEqualTo("generic"); // sanity: checkout is prepared

        service.devReset();

        DevRunService.DevCurrent cur = service.devCurrent();
        assertThat(cur.clientCode()).isNull();
        assertThat(cur.mode()).isNull();
        assertThat(cur.version()).isNull();
    }

    @Test
    void devRun_unknownClient_throwsAndWritesNothing() throws Exception {
        Path be = workspace.resolve("daman-backend/src/main/resources");
        Files.createDirectories(be);
        Files.writeString(be.resolve("client.config.json"), "SENTINEL-UNCHANGED");

        assertThatThrownBy(() -> service.devRun("no-such-client",
                new DevRunService.DevRunRequest("generic", null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readString(be.resolve("client.config.json"))).isEqualTo("SENTINEL-UNCHANGED");
        assertThat(Files.exists(damanHome.resolve("runtime.properties"))).isFalse();
        verify(licenseRepository, never()).save(any());
        verify(licenseKeyService, never()).generateLicense(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** {@link #seedCheckoutGenericFiles()} without the checked exception, for non-throwing test bodies. */
    private void seedCheckoutGenericFiles_unchecked() {
        try {
            seedCheckoutGenericFiles();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
