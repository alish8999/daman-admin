package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreLifecycleService;
import com.daman.admin.service.HostedStoreProvisionLauncher;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.HostedStoreStatusService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HostedStoreControllerTest {

    private static final String SECRET = "super-secret-signing-key-do-not-leak";

    private HostedStoreProvisionLauncher launcher;
    private HostedInstanceRepository repository;
    private ServerOps ops;
    private HostedStoreController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        launcher = mock(HostedStoreProvisionLauncher.class);
        repository = mock(HostedInstanceRepository.class);
        when(repository.findByClientCode(any())).thenReturn(Optional.empty());
        ops = mock(ServerOps.class);
        when(ops.readIngress()).thenReturn("ingress:\n  - service: http_status:404\n");
        // 3 health polls, no sleep: the tests never wait on a real clock.
        controller = new HostedStoreController(launcher, repository, ops,
                new HostedStoreStatusService(ops),
                new HostedStoreLifecycleService(repository, ops, mock(com.daman.admin.repository.LicenseRepository.class), 3, 0), 3, 0);
    }

    /** What the launcher answers for a run it just accepted. */
    private void launcherAccepts(HostedInstance row) {
        when(launcher.start(any())).thenReturn(new HostedStoreProvisionLauncher.Started(row, false));
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
    void suspendMapsToOk409And404() {
        HostedInstance active = row("acme", "ACTIVE");
        HostedInstance provisioning = row("beta", "PROVISIONING");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(active));
        when(repository.findByClientCode("beta")).thenReturn(Optional.of(provisioning));

        assertThat(controller.suspend("acme").getStatusCode().value()).isEqualTo(200);
        assertThat(active.getStatus()).isEqualTo("SUSPENDED");
        assertThat(controller.suspend("beta").getStatusCode().value()).isEqualTo(409);
        assertThat(controller.suspend("ghost").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void resumeAnswers502WhenTheStoreNeverBecomesHealthy() {
        HostedInstance suspended = row("acme", "SUSPENDED");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(suspended));
        when(ops.health(anyInt())).thenReturn(false);

        assertThat(controller.resume("acme").getStatusCode().value()).isEqualTo(502);
        assertThat(suspended.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void restartRefusesASuspendedStoreWith409() {
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(row("acme", "SUSPENDED")));

        assertThat(controller.restartStore("acme").getStatusCode().value()).isEqualTo(409);
        verify(ops, never()).restart(anyString());
    }

    @Test
    void postReturns202WithThePROVISIONINGRowItHandedOff() {
        // 202, not 201: the stage loop runs after this response. Holding the response for the full
        // 125-150 s crosses Cloudflare's 100 s origin timeout, which reported a 524 failure for
        // every provision that in fact succeeded.
        HostedInstance accepted = row("abu_ammar", "PROVISIONING");
        accepted.setFailedStage("ALLOCATE");
        launcherAccepts(accepted);

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        HostedStoreController.HostedStoreDto dto = (HostedStoreController.HostedStoreDto) res.getBody();
        assertThat(dto.clientCode()).isEqualTo("abu_ammar");
        assertThat(dto.status()).isEqualTo("PROVISIONING");
    }

    @Test
    void postReturns400WithTheRejectionReasonForABadSubdomain() {
        when(launcher.start(any())).thenThrow(
                new HostedStoreProvisioningService.RefusedException("Subdomain 'admin' is reserved"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "admin"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Subdomain 'admin' is reserved"));
    }

    @Test
    void aCapacityRefusalIs503NotABadRequest() {
        // Was 400 until this review: the request is fine, the SERVER cannot host another store, and
        // retrying it unchanged once the box is bigger is exactly the right thing to do.
        when(launcher.start(any())).thenThrow(
                new HostedStoreProvisioningService.UnavailableException("Not enough RAM"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Not enough RAM"));
    }

    @Test
    void anIncidentalIllegalStateExceptionIsNotEchoedToTheCaller() {
        // The catch used to be `IllegalArgumentException | IllegalStateException`, wide enough to
        // relay any incidental Spring/JPA state message verbatim as a 400. Now anything that is not
        // one of the three deliberate refusal types propagates and becomes a plain 500.
        when(launcher.start(any())).thenThrow(
                new IllegalStateException("No transactional EntityManager available for jdbc:h2:..."));

        assertThatThrownBy(() -> controller.provision(body("abu_ammar", "abu-ammar")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postReturns409WhenTheClientAlreadyHasAnInstance() {
        HostedInstance active = row("abu_ammar", "ACTIVE");
        when(launcher.start(any()))
                .thenReturn(new HostedStoreProvisionLauncher.Started(active, true));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isInstanceOf(HostedStoreController.HostedStoreDto.class);
    }

    @Test
    void aSecondCreateWhileOneIsStillRunningIs409NotAnotherRun() {
        when(launcher.start(any())).thenThrow(new HostedStoreProvisionLauncher.AlreadyRunningException(
                "A provision for client 'abu_ammar' is already running"));

        ResponseEntity<?> res = controller.provision(body("abu_ammar", "abu-ammar"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().toString()).contains("already running");
    }

    @Test
    void resumingAFailedStoreIsNot409EvenThoughItWillEndActive() {
        when(repository.findByClientCode("abu_ammar")).thenReturn(Optional.of(row("abu_ammar", "FAILED")));
        launcherAccepts(row("abu_ammar", "PROVISIONING"));

        assertThat(controller.provision(body("abu_ammar", "abu-ammar")).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void aBlankOrMissingSubdomainIsRejectedWithoutStartingAnything() {
        for (String sub : new String[]{null, "", "   "}) {
            ResponseEntity<?> res = controller.provision(body("متجر", sub));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(res.getBody().toString()).contains("subdomain").contains("ASCII");
        }
        verifyNoInteractions(launcher);
    }

    @Test
    void aMissingClientCodeIsRejectedWithoutStartingAnything() {
        assertThat(controller.provision(body(" ", "abu-ammar")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.provision(body(null, "abu-ammar")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.provision(null).getStatusCode().value()).isEqualTo(400);
        verify(launcher, never()).start(any());
    }

    // ---- DELETE /{clientCode}: abandon a half-created store ----

    @Test
    void deleteRefusesASuspendedStoreAndPointsAtRemove() {
        // Forgetting a SUSPENDED row would leave its unit and the customer's data on disk with no
        // backup and no row left to drive a proper Remove: the state the removal design prevents.
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(row("acme", "SUSPENDED")));

        ResponseEntity<Map<String, String>> res = controller.forget("acme");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("error")).contains("SUSPENDED").contains("remove");
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteRefusesAnActiveStore() {
        // Spec §5.4's "no suspend, stop or delete" decision is about LIVE stores and still stands.
        when(repository.findByClientCode("abu_ammar")).thenReturn(Optional.of(row("abu_ammar", "ACTIVE")));

        ResponseEntity<Map<String, String>> res = controller.forget("abu_ammar");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("error")).contains("ACTIVE").contains("live store");
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteForgetsAFailedRowAndSaysWhatItDidNotRemove() {
        // The case the spec did not consider: a MISTYPED client code, half-provisioned. Before this
        // route the only escape was an H2 shell over SSH — the resume refuses to rename the row and
        // existsBySubdomain then blocks reusing that subdomain on the correctly-named client.
        HostedInstance failed = row("abu_amar", "FAILED");
        failed.setFailedStage("PAGES_ACTIVE");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(failed));

        ResponseEntity<Map<String, String>> res = controller.forget("abu_amar");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("deleted")).isEqualTo("abu_amar");
        // Registry-only, and it says so: nothing is torn down, exactly as a failed provision tears
        // nothing down.
        assertThat(res.getBody().get("warning"))
                .contains("daman-store-abu_amar")
                .contains("abu-ammar-api.damansoft.com")
                .contains("still exist");
        verify(repository).delete(failed);
    }

    @Test
    void deleteIsRefusedWhileThatClientsProvisionIsStillRunning() {
        // Deleting the row underneath a running stage loop would simply have the loop re-create it.
        when(repository.findByClientCode("abu_ammar")).thenReturn(Optional.of(row("abu_ammar", "PROVISIONING")));
        when(launcher.isRunning("abu_ammar")).thenReturn(true);

        ResponseEntity<Map<String, String>> res = controller.forget("abu_ammar");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("error")).contains("running right now");
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteOfAnUnknownClientIs404() {
        ResponseEntity<Map<String, String>> res = controller.forget("nobody");

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteForgetsAStalePROVISIONINGRowWhenNothingIsRunning() {
        // A crashed run leaves PROVISIONING behind; that is abandonable like any other non-ACTIVE row.
        HostedInstance stale = row("abu_amar", "PROVISIONING");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(stale));
        when(launcher.isRunning(anyString())).thenReturn(false);

        assertThat(controller.forget("abu_amar").getStatusCode().value()).isEqualTo(200);
        verify(repository).delete(stale);
    }

    @Test
    void getListsTheRegistry() {
        when(repository.findAll()).thenReturn(List.of(row("abu_ammar", "ACTIVE"), row("viora", "ACTIVE")));

        assertThat(controller.list().stores()).extracting(HostedStoreController.HostedStoreDto::clientCode)
                .containsExactly("abu_ammar", "viora");
    }

    @Test
    void listCarriesLiveStateAndTheFleetCapacityNumbers() {
        java.time.Instant jarAt = java.time.Instant.parse("2026-10-26T14:56:31Z");
        HostedInstance running = row("abu_ammar", "ACTIVE");
        HostedInstance suspended = row("acme", "SUSPENDED");
        when(repository.findAll()).thenReturn(List.of(running, suspended));
        when(ops.fileModifiedAt("/root/daman-store.jar")).thenReturn(Optional.of(jarAt));
        when(ops.totalMemoryMb()).thenReturn(3814L);
        when(ops.unitActive("daman-store-abu_ammar")).thenReturn(true);
        when(ops.health(9101)).thenReturn(true);
        when(ops.unitStartedAt("daman-store-abu_ammar"))
                .thenReturn(Optional.of(jarAt.minusSeconds(60)));
        when(ops.storeMemoryBytes("daman-store-abu_ammar")).thenReturn(Optional.of(682250240L));
        ServerOps.BoxStats box = new ServerOps.BoxStats(4000000000L, 1600000000L, 0.01, 2, 40000000000L, 36000000000L, 3687582L);
        when(ops.boxStats()).thenReturn(Optional.of(box));

        HostedStoreController.FleetDto fleet = controller.list();
        assertThat(fleet.boxStats()).isEqualTo(box);
        assertThat(fleet.stores().get(0).memoryBytes()).isEqualTo(682250240L);
        assertThat(fleet.stores().get(1).memoryBytes()).isNull();

        assertThat(fleet.jarModifiedAt()).isEqualTo(jarAt);
        assertThat(fleet.totalMemoryMb()).isEqualTo(3814L);
        assertThat(fleet.committedStores()).isEqualTo(2);
        assertThat(fleet.heapMb()).isEqualTo(768);
        assertThat(fleet.maxStores()).isEqualTo(3);
        HostedStoreController.HostedStoreDto first = fleet.stores().get(0);
        assertThat(first.liveStatus()).isEqualTo("running");
        assertThat(first.staleness()).isEqualTo("stale");
        assertThat(first.backupAt()).isEqualTo("unknown");
        assertThat(fleet.stores().get(1).liveStatus()).isEqualTo("suspended");
    }

    @Test
    void listCarriesAFailedRowsStage() {
        HostedInstance failed = row("acme", "FAILED");
        failed.setFailedStage("DNS");
        when(repository.findAll()).thenReturn(List.of(failed));
        HostedStoreController.HostedStoreDto dto = controller.list().stores().get(0);
        assertThat(dto.liveStatus()).isEqualTo("failed");
        assertThat(dto.failedStage()).isEqualTo("DNS");
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
        launcherAccepts(ok);

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

    private List<HostedStoreController.RestartResult> results(ResponseEntity<?> res) {
        return ((HostedStoreController.RestartReport) res.getBody()).walked();
    }

    private List<HostedStoreController.SkippedStore> skipped(ResponseEntity<?> res) {
        return ((HostedStoreController.RestartReport) res.getBody()).skipped();
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
        assertThat(bad.healthy()).isFalse(); assertThat(bad.error()).contains("/api/health");
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
        // The operator sees WHY, not just restarted=false.
        assertThat(results(res).get(0).error()).contains("systemctl failed");
        verify(ops, never()).restart("daman-store-b");
    }

    @Test
    void restartAllSkipsFailedAndProvisioningRowsAndReportsThem() {
        when(repository.findAll()).thenReturn(List.of(
                store("a", 9101, "FAILED"), store("b", 9102, "PROVISIONING"), store("c", 9103, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode).containsExactly("c");
        verify(ops, never()).restart("daman-store-a");
        verify(ops, never()).restart("daman-store-b");
        // Reported, not silently dropped: a store stuck at FAILED while its unit runs fine is
        // exactly how a release becomes a no-op nobody is told about.
        assertThat(skipped(res)).extracting(HostedStoreController.SkippedStore::clientCode)
                .containsExactly("a", "b");
        assertThat(skipped(res).get(0).reason()).contains("not ACTIVE").contains("FAILED");
    }

    @Test
    void restartAllNowIncludesVioraSinceItRunsTheSharedJarToo() {
        // viora was excluded by service name while its unit ran /root/daman-store-viora.jar. That
        // made it the one store a release could not reach - and so the only one that never got a
        // backend fix, which is how it ended up running for weeks with the auto-backup defect.
        // Its unit was moved onto the shared jar on 2026-10-26, so it is an ordinary store here.
        when(repository.findAll()).thenReturn(List.of(store("viora", 8090, "ACTIVE"), store("a", 9101, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        ResponseEntity<?> res = controller.restartAll();

        // Walked in registry order. In production viora's row is the oldest, so from now on the
        // live store that has been running longest is also the canary of every release.
        assertThat(results(res)).extracting(HostedStoreController.RestartResult::clientCode)
                .containsExactly("viora", "a");
        verify(ops).restart("daman-store-viora");
        verify(ops).health(8090);
        assertThat(skipped(res)).isEmpty();
    }

    @Test
    void restartAllReportsAnEmptyWalkRatherThanLookingLikeASuccessfulRelease() {
        // The silent no-op: every row skipped, so the release restarted NOTHING while the jar on
        // disk changed under every store. 200 with an empty walked list is what the script has to
        // shout about — it used to print "every hosted store restarted healthy".
        when(repository.findAll()).thenReturn(List.of(
                store("viora", 8090, "FAILED"), store("a", 9101, "FAILED")));

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(results(res)).isEmpty();
        assertThat(skipped(res)).hasSize(2);
        verifyNoInteractions(ops);
    }

    @Test
    void anEmptyRegistryIsAlsoAnEmptyWalk() {
        when(repository.findAll()).thenReturn(List.of());

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(results(res)).isEmpty();
        assertThat(skipped(res)).isEmpty();
    }

    @Test
    void aSlowStoreIsGivenSeveralHealthPollsBeforeItIsDeclaredUnhealthy() {
        when(repository.findAll()).thenReturn(List.of(store("a", 9101, "ACTIVE")));
        when(ops.health(9101)).thenReturn(false, false, true);

        ResponseEntity<?> res = controller.restartAll();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(results(res).get(0).healthy()).isTrue();
    }

    // ---- forget drops the tunnel rule (a freed port must not keep a live hostname) ----------

    private static final String INGRESS_WITH_RULE =
            "ingress:\n"
            + "  - hostname: admin-api.damansoft.com\n    service: http://localhost:8082\n"
            + "  - hostname: abu-ammar-api.damansoft.com\n    service: http://localhost:9101\n"
            + "  - service: http_status:404\n";

    @Test
    void deleteDropsTheFailedRowsIngressRuleBeforeFreeingItsPort() {
        HostedInstance failed = row("abu_amar", "FAILED");
        failed.setFailedStage("PAGES_ATTACH");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(failed));
        when(ops.readIngress()).thenReturn(INGRESS_WITH_RULE);
        org.mockito.ArgumentCaptor<String> written = org.mockito.ArgumentCaptor.forClass(String.class);

        assertThat(controller.forget("abu_amar").getStatusCode().value()).isEqualTo(200);

        org.mockito.InOrder o = org.mockito.Mockito.inOrder(ops, repository);
        o.verify(ops).writeIngress(written.capture());
        o.verify(ops).reloadCloudflared();
        o.verify(repository).delete(failed);
        assertThat(written.getValue()).doesNotContain("abu-ammar-api.damansoft.com")
                .contains("admin-api.damansoft.com");
    }

    @Test
    void deleteStillSucceedsWhenTheFailedRowHasNoIngressRule() {
        HostedInstance failed = row("abu_amar", "FAILED");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(failed));

        assertThat(controller.forget("abu_amar").getStatusCode().value()).isEqualTo(200);

        verify(ops, never()).writeIngress(anyString());
        verify(ops, never()).reloadCloudflared();
        verify(repository).delete(failed);
    }

    @Test
    void deleteNeverAimsAnIngressRemovalAtAHostnameThatIsNotTheRowsOwn() {
        HostedInstance bad = row("abu_amar", "FAILED");
        bad.setApiHostname("admin-api.damansoft.com");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(bad));

        assertThat(controller.forget("abu_amar").getStatusCode().value()).isEqualTo(200);

        verify(ops, never()).writeIngress(anyString());
    }

    // ---- POST /{clientCode}/remove ----------------------------------------------------------

    @Test
    void removeAnswers200WithTheResultForASuspendedStore() {
        HostedInstance suspended = row("abu_amar", "SUSPENDED");
        suspended.setDataDir("/root/stores/abu_amar");
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(suspended));
        when(ops.fileModifiedAt(anyString())).thenReturn(Optional.of(java.time.Instant.now()));

        ResponseEntity<?> res = controller.remove("abu_amar");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isInstanceOf(HostedStoreLifecycleService.RemoveResult.class);
        HostedStoreLifecycleService.RemoveResult r = (HostedStoreLifecycleService.RemoveResult) res.getBody();
        assertThat(r.clientCode()).isEqualTo("abu_amar");
        assertThat(r.keptBackup()).startsWith("/root/removed-stores/abu_amar-");
        verify(ops).deleteTree("/root/stores/abu_amar", "abu_amar");
        verify(repository).delete(suspended);
    }

    @Test
    void removeRefusesANonSuspendedStoreWith409AndTouchesNothing() {
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(row("abu_amar", "ACTIVE")));

        ResponseEntity<?> res = controller.remove("abu_amar");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        verify(ops, never()).stopAndDisable(anyString());
        verify(ops, never()).deleteTree(any(), any());
        verify(repository, never()).delete(any());
    }

    @Test
    void removeOfAnUnknownClientIs404() {
        assertThat(controller.remove("nobody").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void removeIsRefusedWhileThatClientsProvisionIsStillRunning() {
        when(repository.findByClientCode("abu_amar")).thenReturn(Optional.of(row("abu_amar", "SUSPENDED")));
        when(launcher.isRunning("abu_amar")).thenReturn(true);

        assertThat(controller.remove("abu_amar").getStatusCode().value()).isEqualTo(409);
        verify(ops, never()).deleteTree(any(), any());
        verify(repository, never()).delete(any());
    }
}
