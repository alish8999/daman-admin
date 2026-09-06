package com.daman.admin.service;

import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.dto.FeaturesRequest;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientConfigServiceTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void export_roundTripsSimulatePosModeFromPersistedFeaturesJson() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

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
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

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
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

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
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

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
    void licenseEntitlementsFor_returnsBaseCurrencyAndFeatureMapFromStoredConfig() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

        // Arrange: a stored ClientConfig with baseCurrency=SYP and a couple of non-default flags on.
        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode("acme");
        cfg.setAppName("Acme");
        cfg.setBaseCurrency("SYP");
        // featuresJson with posTerminals + accounting true, rest default:
        cfg.setFeaturesJson("{\"posTerminals\":true,\"accounting\":true}");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(cfg));

        // Act
        ClientConfigService.LicenseEntitlements ent = service.licenseEntitlementsFor("acme");

        // Assert
        assertThat(ent.baseCurrency()).isEqualTo("SYP");
        assertThat(ent.features()).containsEntry("posTerminals", true);
        assertThat(ent.features()).containsEntry("accounting", true);
        assertThat(ent.features()).containsEntry("barcode", false);       // an omitted flag → its default
        assertThat(ent.features()).containsKey("multiCurrency");          // the "special" flags are present too
        assertThat(ent.features()).containsKey("autoBackup");
    }

    @Test
    void licenseEntitlementsFor_unknownClient_throws() {
        ClientConfigRepository repository = mock(ClientConfigRepository.class);
        ClientConfigService service = new ClientConfigService(repository, objectMapper);

        when(repository.findByClientCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.licenseEntitlementsFor("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Client not found");
    }
}
