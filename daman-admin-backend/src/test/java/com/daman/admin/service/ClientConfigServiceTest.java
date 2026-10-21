package com.daman.admin.service;

import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.dto.FeaturesRequest;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientConfigServiceTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    /**
     * The delete cascade needs the four repositories that carry a clientCode column. Tests
     * that do not exercise delete get harmless mocks; the delete tests stub them.
     */
    private ClientConfigService newService(ClientConfigRepository repository) {
        return new ClientConfigService(repository, objectMapper,
                mock(com.daman.admin.repository.LicenseRepository.class),
                mock(com.daman.admin.repository.BillingRepository.class),
                mock(com.daman.admin.repository.BuildLogRepository.class),
                mock(com.daman.admin.repository.TrialRegistrationRepository.class));
    }

    @Test
    void export_roundTripsSimulatePosModeFromPersistedFeaturesJson() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        ClientConfig entity = new ClientConfig();
        entity.setClientCode("acme");
        entity.setAppName("Acme POS");
        // simulatePosMode:true, every other feature default/false — mirrors how a real
        // persisted row looks after only that one toggle was ever flipped on.
        entity.setFeaturesJson("{\"simulatePosMode\":true}");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(entity));

        ClientConfigExportDto result = service.export("acme");

        assertThat(result.getFeatures().isSimulatePosMode()).isTrue();
    }

    @Test
    void export_roundTripsFixedAssetsFromPersistedFeaturesJson() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        ClientConfig entity = new ClientConfig();
        entity.setClientCode("acme");
        entity.setAppName("Acme POS");
        entity.setFeaturesJson("{\"fixedAssets\":true}");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(entity));

        ClientConfigExportDto result = service.export("acme");

        assertThat(result.getFeatures().isFixedAssets()).isTrue();
    }

    @Test
    void create_persistsFixedAssetsFromFeaturesRequestPatch() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        when(repository.existsByClientCode("acme")).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setClientCode("acme");
        request.setAppName("Acme POS");
        FeaturesRequest features = new FeaturesRequest();
        features.setFixedAssets(true);
        request.setFeatures(features);

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFeaturesJson()).contains("\"fixedAssets\":true");
    }

    @Test
    void create_persistsSimulatePosModeFromFeaturesRequestPatch() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        when(repository.existsByClientCode("acme")).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setClientCode("acme");
        request.setAppName("Acme POS");
        FeaturesRequest features = new FeaturesRequest();
        features.setSimulatePosMode(true);
        request.setFeatures(features);

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFeaturesJson()).contains("\"simulatePosMode\":true");
    }

    @Test
    void create_blankClientCode_autoGeneratesSlugFromAppName_andSetsTaglineToAppName() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        when(repository.existsByClientCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("Diaa  Mart!");   // clientCode + tagline deliberately unset

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getClientCode()).isEqualTo("diaa-mart");
        assertThat(captor.getValue().getTagline()).isEqualTo("Diaa  Mart!");
    }

    @Test
    void create_arabicOnlyAppName_fallsBackToClientHexCode() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        when(repository.existsByClientCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("رقية دريم");

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getClientCode()).matches("client-[0-9a-f]{6}");
    }

    @Test
    void create_mirrorsSingleLogoToLightSlot_andForcesDefaultFavicon() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        when(repository.existsByClientCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("Acme");
        request.setLogoDark("data:image/png;base64,AAAA");
        request.setFavicon("data:image/x-icon;base64,ZZZZ");   // caller tries a custom favicon

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLogoDark()).isEqualTo("data:image/png;base64,AAAA");
        assertThat(captor.getValue().getLogoLight()).isEqualTo("data:image/png;base64,AAAA"); // mirrored
        assertThat(captor.getValue().getFavicon()).isEqualTo("favicon.ico");                  // forced default
    }

    @Test
    void create_slugCollision_appendsCounter() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        when(repository.existsByClientCode("acme-pos")).thenReturn(true);
        when(repository.existsByClientCode("acme-pos-2")).thenReturn(false);
        when(repository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("Acme POS");

        service.create(request);

        org.mockito.ArgumentCaptor<ClientConfig> captor = org.mockito.ArgumentCaptor.forClass(ClientConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getClientCode()).isEqualTo("acme-pos-2");
    }

    @Test
    void licenseEntitlementsFor_returnsBaseCurrencyAndFeatureMapFromStoredConfig() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        // Arrange: a stored ClientConfig with baseCurrency=SYP and a couple of non-default flags on.
        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode("acme");
        cfg.setAppName("Acme");
        cfg.setBaseCurrency("SYP");
        cfg.setColorPrimary("#ed5b1d");
        cfg.setColorSecondary("#587adf");
        // featuresJson with posTerminals + accounting true, rest default:
        cfg.setFeaturesJson("{\"posTerminals\":true,\"accounting\":true}");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(cfg));

        // Act
        ClientConfigService.LicenseEntitlements ent = service.licenseEntitlementsFor("acme");

        // Assert
        assertThat(ent.baseCurrency()).isEqualTo("SYP");
        assertThat(ent.colorPrimary()).isEqualTo("#ed5b1d");
        assertThat(ent.colorSecondary()).isEqualTo("#587adf");
        assertThat(ent.features()).containsEntry("posTerminals", true);
        assertThat(ent.features()).containsEntry("accounting", true);
        assertThat(ent.features()).containsEntry("barcode", false);       // an omitted flag → its default
        assertThat(ent.features()).containsKey("multiCurrency");          // the "special" flags are present too
        assertThat(ent.features()).containsKey("autoBackup");
    }

    @Test
    void prepareDevConfig_writesThreeCheckoutFilesWithExportedJson_andRuntimeProperties(@TempDir Path workspace) throws Exception {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        ReflectionTestUtils.setField(service, "workspaceRoot", workspace.toString());

        ClientConfig entity = new ClientConfig();
        entity.setClientCode("acme");
        entity.setAppName("Acme POS");
        entity.setBaseCurrency("SYP");
        entity.setFeaturesJson("{\"accounting\":true}");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(entity));

        // Point user.home at the temp workspace so this test never touches the real ~/.daman.
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", workspace.resolve("userhome").toString());
        try {
            service.prepareDevConfig("acme");
        } finally {
            System.setProperty("user.home", originalUserHome);
        }

        Path backendJson  = workspace.resolve("daman-backend/src/main/resources/client.config.json");
        Path frontendJson = workspace.resolve("daman-frontend/src/assets/client.config.json");
        Path backendMeta  = workspace.resolve("daman-backend/src/main/resources/client-meta.properties");
        assertThat(backendJson).exists();
        assertThat(frontendJson).exists();
        assertThat(backendMeta).exists();

        String json = Files.readString(backendJson);
        assertThat(json).contains("\"clientCode\"").contains("\"acme\"")
                .contains("\"baseCurrency\"").contains("\"SYP\"")
                .contains("\"accounting\"");
        assertThat(Files.readString(frontendJson)).isEqualTo(json);
        assertThat(Files.readString(backendMeta))
                .contains("client.code=acme")
                .contains("client.version=dev");
        assertThat(Files.readString(workspace.resolve("userhome/.daman/runtime.properties")).trim())
                .isEqualTo("daman.runtime.client-code=acme");
    }

    @Test
    void licenseEntitlementsFor_unknownClient_throws() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);

        when(repository.findByClientCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.licenseEntitlementsFor("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Client not found");
    }

    @Test
    void licenseEntitlementsFor_buildsProfileFromStoredConfig() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode("acme");
        cfg.setAppName("Acme");
        cfg.setPhone("0999");
        cfg.setAddress("Aleppo");
        cfg.setStoreType("cafe");
        cfg.setHasTables(false);
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(cfg));

        LicenseProfile p = service.licenseEntitlementsFor("acme").profile();

        assertThat(p).isEqualTo(new LicenseProfile("Acme", "0999", "Aleppo", "cafe", false));
    }

    @Test
    void licenseEntitlementsFor_legacyRowWithNullProfileFields_doesNotThrow() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = newService(repository);
        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode("old");
        cfg.setAppName("Old Shop");
        when(repository.findByClientCode("old")).thenReturn(Optional.of(cfg));

        LicenseProfile p = service.licenseEntitlementsFor("old").profile();

        assertThat(p.phone()).isNull();
        assertThat(p.hasTables()).isNull();
    }

    // ── delete: cascade, except when real money came in ────────────────────────
    // Deleting used to remove the client row alone, orphaning four tables - which left the
    // client's app running, let the renewal endpoint keep serving an orphaned licence, held
    // that machine's trial index forever, and let the next trial inherit a recycled code.

    private record DeleteFixture(ClientConfigService service,
                                 ClientConfigRepository clients,
                                 com.daman.admin.repository.LicenseRepository licenses,
                                 com.daman.admin.repository.BillingRepository billings,
                                 com.daman.admin.repository.BuildLogRepository builds,
                                 com.daman.admin.repository.TrialRegistrationRepository trials) {}

    private DeleteFixture deleteFixture(String... billingStatuses) {
        ClientConfigRepository clients = mock(ClientConfigRepository.class);
        var licenses = mock(com.daman.admin.repository.LicenseRepository.class);
        var billings = mock(com.daman.admin.repository.BillingRepository.class);
        var builds = mock(com.daman.admin.repository.BuildLogRepository.class);
        var trials = mock(com.daman.admin.repository.TrialRegistrationRepository.class);

        ClientConfig entity = new ClientConfig();
        entity.setClientCode("c-7");
        when(clients.findByClientCode("c-7")).thenReturn(Optional.of(entity));

        java.util.List<com.daman.admin.entity.Billing> rows = new java.util.ArrayList<>();
        for (String s : billingStatuses) {
            com.daman.admin.entity.Billing b = new com.daman.admin.entity.Billing();
            b.setClientCode("c-7");
            b.setPaymentStatus(s);
            rows.add(b);
        }
        when(billings.findByClientCodeOrderByCreatedAtDesc("c-7")).thenReturn(rows);
        when(licenses.findByClientCodeOrderByActivatedAtDesc("c-7")).thenReturn(java.util.List.of());
        when(builds.findByClientCodeOrderByStartedAtDesc("c-7")).thenReturn(java.util.List.of());
        when(trials.findAllByClientCode("c-7")).thenReturn(java.util.List.of());

        return new DeleteFixture(
                new ClientConfigService(clients, objectMapper, licenses, billings, builds, trials),
                clients, licenses, billings, builds, trials);
    }

    @Test
    void deletingATrialClientClearsEveryTableKeyedToItsCode() {
        DeleteFixture f = deleteFixture();

        f.service().delete("c-7");

        verify(f.licenses()).deleteAll(any());
        verify(f.trials()).deleteAll(any());
        verify(f.builds()).deleteAll(any());
        verify(f.billings()).deleteAll(any());
        verify(f.clients()).delete(any());
    }

    /** An invoice nobody has paid must not block clearing out a dead trial. */
    @Test
    void aPendingInvoiceDoesNotBlockTheDelete() {
        DeleteFixture f = deleteFixture("PENDING");

        f.service().delete("c-7");

        verify(f.clients()).delete(any());
    }

    @Test
    void aPaidClientIsRefusedAndNothingIsTouched() {
        DeleteFixture f = deleteFixture("PAID");

        assertThatThrownBy(() -> f.service().delete("c-7"))
                .isInstanceOf(ClientHasPaymentsException.class);

        verify(f.clients(), never()).delete(any());
        verify(f.licenses(), never()).deleteAll(any());
        verify(f.trials(), never()).deleteAll(any());
        verify(f.billings(), never()).deleteAll(any());
    }

    /** PARTIAL means some money arrived, so it protects the record just as PAID does. */
    @Test
    void aPartiallyPaidClientIsAlsoRefused() {
        DeleteFixture f = deleteFixture("PENDING", "PARTIAL");

        assertThatThrownBy(() -> f.service().delete("c-7"))
                .isInstanceOf(ClientHasPaymentsException.class);
        verify(f.clients(), never()).delete(any());
    }

    @Test
    void theStatusCheckIsCaseAndWhitespaceTolerant() {
        DeleteFixture f = deleteFixture("  paid ");

        assertThatThrownBy(() -> f.service().delete("c-7"))
                .isInstanceOf(ClientHasPaymentsException.class);
    }

    @Test
    void deletingAnUnknownClientStillFails() {
        DeleteFixture f = deleteFixture();
        when(f.clients().findByClientCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> f.service().delete("nope")).isInstanceOf(RuntimeException.class);
    }
}
