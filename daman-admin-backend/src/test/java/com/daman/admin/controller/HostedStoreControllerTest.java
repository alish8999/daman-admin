package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HostedStoreControllerTest {

    private static final String SECRET = "super-secret-signing-key-do-not-leak";

    private HostedStoreProvisioningService service;
    private HostedInstanceRepository repository;
    private HostedStoreController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        service = mock(HostedStoreProvisioningService.class);
        repository = mock(HostedInstanceRepository.class);
        when(repository.findByClientCode(any())).thenReturn(Optional.empty());
        controller = new HostedStoreController(service, repository);
    }

    private HostedInstance row(String code, String status) {
        HostedInstance i = new HostedInstance();
        i.setId(1L);
        i.setClientCode(code);
        i.setSubdomain("abu-ammar");
        i.setPort(9101);
        i.setServiceName("daman-store-" + code);
        i.setDataDir("/var/lib/daman/" + code);
        i.setApiHostname("abu-ammar-api.damansoft.com");
        i.setFrontendHostname("abu-ammar.damansoft.com");
        i.setJwtSecret(SECRET);
        i.setStatus(status);
        return i;
    }

    private HostedStoreController.ProvisionBody body(String code, String sub) {
        return new HostedStoreController.ProvisionBody(code, sub, null);
    }

    @Test
    void postReturns201WithTheProvisionedInstance() {
        when(service.provision(any())).thenReturn(row("abu_ammar", "ACTIVE"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(((HostedStoreController.HostedStoreDto) res.getBody()).clientCode()).isEqualTo("abu_ammar");
    }

    @Test
    void aFailedRunStillReturns201WithTheFailedStage() {
        HostedInstance failed = row("abu_ammar", "FAILED");
        failed.setFailedStage("DNS_API");
        when(service.provision(any())).thenReturn(failed);

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        HostedStoreController.HostedStoreDto dto = (HostedStoreController.HostedStoreDto) res.getBody();
        assertThat(dto.status()).isEqualTo("FAILED");
        assertThat(dto.failedStage()).isEqualTo("DNS_API");
    }

    @Test
    void postReturns400WithTheRejectionReasonForABadSubdomain() {
        when(service.provision(any())).thenThrow(new IllegalArgumentException("Subdomain 'admin' is reserved"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "admin"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Subdomain 'admin' is reserved"));
    }

    @Test
    void aCapacityRefusalIsAlso400() {
        when(service.provision(any())).thenThrow(new IllegalStateException("Not enough free RAM"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Not enough free RAM"));
    }

    @Test
    void postReturns409WhenTheClientAlreadyHasAnInstance() {
        when(repository.findByClientCode("abu_ammar")).thenReturn(Optional.of(row("abu_ammar", "ACTIVE")));
        when(service.provision(any())).thenReturn(row("abu_ammar", "ACTIVE"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isInstanceOf(HostedStoreController.HostedStoreDto.class);
    }

    @Test
    void resumingAFailedStoreIsNot409EvenThoughItNowEndsActive() {
        when(repository.findByClientCode("abu_ammar")).thenReturn(Optional.of(row("abu_ammar", "FAILED")));
        when(service.provision(any())).thenReturn(row("abu_ammar", "ACTIVE"));

        assertThat(controller.provision(body("abu_ammar", "abu-ammar")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aBlankOrMissingSubdomainIsRejectedWithoutCallingTheService() {
        for (String sub : new String[]{null, "", "   "}) {
            ResponseEntity<?> res = controller.provision(body("متجر", sub));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(res.getBody().toString()).contains("subdomain").contains("ASCII");
        }
        verifyNoInteractions(service);
    }

    @Test
    void aMissingClientCodeIsRejectedWithoutCallingTheService() {
        assertThat(controller.provision(body(" ", "abu-ammar")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.provision(body(null, "abu-ammar")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.provision(null).getStatusCode().value()).isEqualTo(400);
        verify(service, never()).provision(any());
    }

    @Test
    void getListsTheRegistry() {
        when(repository.findAll()).thenReturn(List.of(row("abu_ammar", "ACTIVE"), row("viora", "ACTIVE")));

        assertThat(controller.list()).extracting(HostedStoreController.HostedStoreDto::clientCode)
                .containsExactly("abu_ammar", "viora");
    }

    @Test
    void suggestSubdomainSanitisesTheClientCode() {
        assertThat(controller.suggestSubdomain("abu_ammar")).isEqualTo(Map.of("subdomain", "abu-ammar"));
    }

    @Test
    void suggestSubdomainReturnsEmptyHonestlyForAnArabicOnlyCode() {
        assertThat(controller.suggestSubdomain("متجر")).isEqualTo(Map.of("subdomain", ""));
    }

    @Test
    void responsesNeverIncludeTheStoredJwtSecret() throws Exception {
        HostedInstance ok = row("abu_ammar", "ACTIVE");
        when(repository.findAll()).thenReturn(List.of(ok));
        when(service.provision(any())).thenReturn(ok);

        String created = mapper.writeValueAsString(controller.provision(body("abu_ammar", "abu-ammar")).getBody());
        String listed = mapper.writeValueAsString(controller.list());

        assertThat(created).doesNotContain(SECRET).doesNotContain("jwtSecret");
        assertThat(listed).doesNotContain(SECRET).doesNotContain("jwtSecret");
        // Structural: the DTO type itself cannot carry it, whatever a mapper does.
        assertThat(HostedStoreController.HostedStoreDto.class.getRecordComponents())
                .extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.contains("secret") || n.contains("jwt"));
    }
}
