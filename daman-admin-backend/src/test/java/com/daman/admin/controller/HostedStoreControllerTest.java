package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.ServerOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HostedStoreControllerTest {

    private static final String SECRET = "super-secret-signing-key-do-not-leak";

    private HostedStoreProvisioningService service;
    private HostedInstanceRepository repository;
    private ServerOps ops;
    private HostedStoreController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        service = mock(HostedStoreProvisioningService.class);
        repository = mock(HostedInstanceRepository.class);
        when(repository.findByClientCode(any())).thenReturn(Optional.empty());
        ops = mock(ServerOps.class);
        // 3 health polls, no sleep: the tests never wait on a real clock.
        controller = new HostedStoreController(service, repository, ops, 3, 0);
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
    // ---- POST /restart-all: shared-jar release, one store at a time ----

    private HostedInstance store(String code, int port, String status) {
        HostedInstance i = row(code, status);
        i.setPort(port);
        return i;
    }

    @SuppressWarnings("unchecked")
    private List<HostedStoreController.RestartResult> results(ResponseEntity<?> res) {
        return (List<HostedStoreController.RestartResult>) res.getBody();
    }

    @Test
    void restartAllWalksEveryActiveInstance() {
        when(repository.findAll()).thenReturn(List.of(
                store("a", 9101, "ACTIVE"), store("b", 9102, "ACTIVE"), store("c", 9103, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode)
                .containsExactly("a", "b", "c");
        assertThat(results(res)).allMatch(r -> r.restarted() && r.healthy());
        // One at a time: each store is restarted and checked before the next is touched.
        InOrder order = inOrder(ops);
        order.verify(ops).restart("daman-store-a");
        order.verify(ops).health(9101);
        order.verify(ops).restart("daman-store-b");
        order.verify(ops).health(9102);
        order.verify(ops).restart("daman-store-c");
        order.verify(ops).health(9103);
    }

    @Test
    void restartAllStopsAtTheFirstUnhealthyStoreAndReportsIt() {
        when(repository.findAll()).thenReturn(List.of(
                store("a", 9101, "ACTIVE"), store("b", 9102, "ACTIVE"), store("c", 9103, "ACTIVE")));
        when(ops.health(9101)).thenReturn(true);
        when(ops.health(9102)).thenReturn(false);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode)
                .containsExactly("a", "b");
        HostedStoreController.RestartResult bad = results(res).get(1);
        assertThat(bad.restarted()).isTrue();
        assertThat(bad.healthy()).isFalse();
        // Later stores are NOT restarted - that is the point of the walk.
        verify(ops, never()).restart("daman-store-c");
        verify(ops, never()).health(9103);
    }

    @Test
    void restartAllStopsWhenARestartItselfThrows() {
        when(repository.findAll()).thenReturn(List.of(store("a", 9101, "ACTIVE"), store("b", 9102, "ACTIVE")));
        doThrow(new IllegalStateException("systemctl failed")).when(ops).restart("daman-store-a");

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat(results(res)).hasSize(1);
        assertThat(results(res).get(0).restarted()).isFalse();
        assertThat(results(res).get(0).healthy()).isFalse();
        verify(ops, never()).restart("daman-store-b");
    }

    @Test
    void restartAllSkipsFailedAndProvisioningRows() {
        when(repository.findAll()).thenReturn(List.of(
                store("a", 9101, "FAILED"), store("b", 9102, "PROVISIONING"), store("c", 9103, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode).containsExactly("c");
        verify(ops, never()).restart("daman-store-a");
        verify(ops, never()).restart("daman-store-b");
    }

    @Test
    void restartAllExcludesVioraWhichRunsItsOwnJar() {
        // viora's unit points at /root/daman-store-viora.jar, not the shared jar, and is frozen:
        // a shared-jar release must not bounce a live paying store that is not running it.
        when(repository.findAll()).thenReturn(List.of(store("viora", 8090, "ACTIVE"), store("a", 9101, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode).containsExactly("a");
        verify(ops, never()).restart("daman-store-viora");
        verify(ops, never()).health(8090);
    }

    @Test
    void aSlowStoreIsGivenSeveralHealthPollsBeforeItIsDeclaredUnhealthy() {
        when(repository.findAll()).thenReturn(List.of(store("a", 9101, "ACTIVE")));
        when(ops.health(9101)).thenReturn(false, false, true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(results(res).get(0).healthy()).isTrue();
    }
}
