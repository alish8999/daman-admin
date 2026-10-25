package com.daman.admin.service;

import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.HostedInstance;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link HostedStoreProvisioningService} entirely through fakes: a {@link ServerOps}
 * fake and a {@link CloudflareClient} subclass whose HTTP seam throws if it is ever reached.
 * Nothing here shells out, writes to /etc or contacts Cloudflare — both fakes append to one
 * shared ordered call log, which is what the ordering and "no duplicate on resume" cases assert on.
 */
class HostedStoreProvisioningServiceTest {

    private static final String MACHINE_ID = "AABBCCDD1122334455";
    private static final String DOMAIN = "damansoft.com";
    private static final String PAGES_PROJECT = "daman-store";
    private static final String TUNNEL_ID = "4f0ba07b-dfae-4929-bf81-3cefe14cdaaa";
    private static final String TUNNEL_TARGET = TUNNEL_ID + ".cfargotunnel.com";
    private static final String PAGES_TARGET = PAGES_PROJECT + ".pages.dev";
    private static final String MINTED_KEY = "LICENCE-KEY";

    /** The live /etc/cloudflared/config.yml shape (see IngressEditorTest). */
    private static final String LIVE_INGRESS = """
            tunnel: 4f0ba07b-dfae-4929-bf81-3cefe14cdaaa
            credentials-file: /root/.cloudflared/4f0ba07b-dfae-4929-bf81-3cefe14cdaaa.json

            ingress:
              - hostname: admin-api.damansoft.com
                service: http://localhost:8083
              - hostname: viora-api.damansoft.com
                service: http://localhost:8090
              - service: http_status:404
            """;

    private final List<String> calls = new ArrayList<>();
    private final List<HostedInstance> instances = new ArrayList<>();
    private final List<License> licences = new ArrayList<>();

    private FakeServerOps ops;
    private FakeCloudflare cloudflare;
    private HostedInstanceRepository hostedRepo;
    private LicenseRepository licenseRepo;
    private LicenseKeyService licenseKeyService;
    private HostedStoreProvisioningService service;

    @BeforeEach
    void setUp() {
        ops = new FakeServerOps();
        cloudflare = new FakeCloudflare();

        hostedRepo = mock(HostedInstanceRepository.class);
        when(hostedRepo.findByClientCode(anyString())).thenAnswer(i -> instances.stream()
                .filter(r -> r.getClientCode().equals(i.<String>getArgument(0))).findFirst());
        when(hostedRepo.existsBySubdomain(anyString())).thenAnswer(i -> instances.stream()
                .anyMatch(r -> r.getSubdomain().equals(i.<String>getArgument(0))));
        when(hostedRepo.findAll()).thenAnswer(i -> new ArrayList<>(instances));
        when(hostedRepo.save(any(HostedInstance.class))).thenAnswer(i -> {
            HostedInstance row = i.getArgument(0);
            if (!instances.contains(row)) {
                row.setId((long) (instances.size() + 1));
                instances.add(row);
            }
            return row;
        });

        ClientConfigRepository clientConfigRepo = mock(ClientConfigRepository.class);
        when(clientConfigRepo.findByClientCode(anyString())).thenAnswer(i -> {
            String code = i.getArgument(0);
            if (!List.of("acme", "beta").contains(code)) return Optional.empty();
            ClientConfig c = new ClientConfig();
            c.setClientCode(code);
            c.setAppName(code.toUpperCase() + " Store");
            return Optional.of(c);
        });

        ClientConfigService clientConfigService = mock(ClientConfigService.class);
        when(clientConfigService.licenseEntitlementsFor(anyString())).thenAnswer(i ->
                new ClientConfigService.LicenseEntitlements("SYP", Map.of("accounting", true),
                        "#111111", "#222222", "owner", "pw",
                        new LicenseProfile(i.getArgument(0) + " Store", null, null, "mobile", false)));

        licenseKeyService = mock(LicenseKeyService.class);
        when(licenseKeyService.generateLicense(anyString(), any(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(MINTED_KEY);

        licenseRepo = mock(LicenseRepository.class);
        when(licenseRepo.findByMachineIdAndClientCodeAndStatus(anyString(), anyString(), anyString()))
                .thenAnswer(i -> licences.stream()
                        .filter(l -> l.getMachineId().equals(i.<String>getArgument(0))
                                && l.getClientCode().equals(i.<String>getArgument(1))
                                && l.getStatus().equals(i.<String>getArgument(2)))
                        .findFirst());
        when(licenseRepo.save(any(License.class))).thenAnswer(i -> {
            License l = i.getArgument(0);
            if (!licences.contains(l)) licences.add(l);
            return l;
        });

        service = new HostedStoreProvisioningService(hostedRepo, clientConfigRepo, clientConfigService,
                licenseKeyService, licenseRepo, cloudflare, ops);
        ReflectionTestUtils.setField(service, "hostedDomain", DOMAIN);
        ReflectionTestUtils.setField(service, "pagesProject", PAGES_PROJECT);
        ReflectionTestUtils.setField(service, "tunnelId", TUNNEL_ID);
        ReflectionTestUtils.setField(service, "cloudflareAccountId", "acct-id");
        ReflectionTestUtils.setField(service, "cloudflareZoneId", "zone-id");
        // No real waiting: every bounded poll still runs its full attempt budget, instantly.
        ReflectionTestUtils.setField(service, "sleeper",
                (HostedStoreProvisioningService.Sleeper) millis -> { });
    }

    @Test
    void happyPathWritesRegistryRowAsActive() {
        HostedInstance i = service.provision(new ProvisionRequest("acme", "acme", "2027-01-31"));

        assertThat(i.getStatus()).isEqualTo("ACTIVE");
        assertThat(i.getFailedStage()).isNull();
        assertThat(i.getPort()).isEqualTo(8091);
        assertThat(i.getServiceName()).isEqualTo("daman-store-acme");
        assertThat(i.getDataDir()).isEqualTo("/root/stores/acme");
        assertThat(i.getApiHostname()).isEqualTo("acme-api.damansoft.com");
        assertThat(i.getFrontendHostname()).isEqualTo("acme.damansoft.com");
        assertThat(i.getJwtSecret()).isNotBlank();
        assertThat(i.getDnsRecordId()).isNotBlank();
        assertThat(instances).hasSize(1);

        // The unit the store actually runs agrees with the registry's dataDir, and carries
        // this store's own port and secret.
        String unit = ops.units.get("daman-store-acme");
        assertThat(unit).contains("-Duser.home=" + i.getDataDir() + " ");
        assertThat(unit).contains("Environment=DAMAN_PORT=8091");
        assertThat(unit).contains("Environment=DAMAN_JWT_SECRET=" + i.getJwtSecret());
        assertThat(unit).doesNotContain("\r");
        assertThat(calls).contains("mkdir:/root/stores/acme", "start:daman-store-acme");

        // Ingress gained only this store's API rule; the catch-all is still last.
        assertThat(ops.ingress).contains("""
                  - hostname: acme-api.damansoft.com
                    service: http://localhost:8091
                  - service: http_status:404
                """);
        assertThat(ops.ingress).contains("- hostname: viora-api.damansoft.com");
        assertThat(calls).contains("reloadCloudflared");

        // Licence minted for the store's own machine id and activated on the store itself.
        assertThat(ops.activatedKeys).containsExactly(MINTED_KEY);
        assertThat(licences).hasSize(1);
        assertThat(licences.get(0).getMachineId()).isEqualTo(MACHINE_ID);
        assertThat(licences.get(0).getClientCode()).isEqualTo("acme");
        assertThat(licences.get(0).getLicenseKey()).isEqualTo(MINTED_KEY);
        assertThat(licences.get(0).getExpiresAt()).isEqualTo(LocalDate.parse("2027-01-31"));
    }

    @Test
    void rejectsUnknownClientCode() {
        assertThatThrownBy(() -> service.provision(new ProvisionRequest("ghost", "ghost", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");

        assertThat(instances).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsReservedSubdomain() {
        for (String reserved : List.of("admin", "admin-api", "dl", "www", "viora", "acme-api")) {
            assertThatThrownBy(() -> service.provision(new ProvisionRequest("acme", reserved, null)))
                    .as(reserved)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved");
        }
        assertThat(instances).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsInvalidSubdomain() {
        for (String bad : List.of("abu_ammar", "-shop", "shop-", "Shop", "a.b", "x".repeat(33), " ")) {
            assertThatThrownBy(() -> service.provision(new ProvisionRequest("acme", bad, null)))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subdomain");
        }
        assertThat(instances).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsSubdomainAlreadyInRegistry() {
        instances.add(row("beta", "acme", 8091, "ACTIVE", null));

        assertThatThrownBy(() -> service.provision(new ProvisionRequest("acme", "acme", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already");

        assertThat(instances).hasSize(1);
        assertThat(calls).isEmpty();
    }

    @Test
    void refusesWhenCapacityGuardFails() {
        ops.availableMb = 900;

        assertThatThrownBy(() -> service.provision(new ProvisionRequest("acme", "acme", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAM");

        assertThat(instances).isEmpty();
        assertThat(calls).noneMatch(c -> c.startsWith("writeUnit"));
        assertThat(calls).noneMatch(c -> c.startsWith("dns:create"));
    }

    @Test
    void createsDnsBeforeAttachingPagesDomain() {
        service.provision(new ProvisionRequest("acme", "acme", null));

        int apiDns = calls.indexOf("dns:create:acme-api." + DOMAIN + "->" + TUNNEL_TARGET);
        int frontendDns = calls.indexOf("dns:create:acme." + DOMAIN + "->" + PAGES_TARGET);
        int attach = calls.indexOf("pages:attach:acme." + DOMAIN);

        assertThat(apiDns).isNotNegative();
        assertThat(frontendDns).isNotNegative();
        // Attaching a Pages domain before its DNS record exists leaves it pending forever.
        assertThat(attach).isGreaterThan(frontendDns).isGreaterThan(apiDns);
        // ...and only then is activation polled.
        assertThat(calls.subList(attach + 1, calls.size()))
                .contains("pages:status:acme." + DOMAIN);
    }

    @Test
    void secondProvisionGetsADistinctPortAndJwtSecret() {
        HostedInstance first = service.provision(new ProvisionRequest("acme", "acme", null));
        HostedInstance second = service.provision(new ProvisionRequest("beta", "beta", null));

        assertThat(first.getPort()).isEqualTo(8091);
        assertThat(second.getPort()).isEqualTo(8092);
        assertThat(second.getServiceName()).isEqualTo("daman-store-beta");
        assertThat(second.getStatus()).isEqualTo("ACTIVE");
        assertThat(second.getJwtSecret()).isNotBlank().isNotEqualTo(first.getJwtSecret());
        assertThat(ops.units.get("daman-store-beta")).contains("Environment=DAMAN_PORT=8092");
    }

    @Test
    void failureAtIngressPersistsFailedRowWithStage() {
        ops.failWriteIngress = new IllegalStateException("config.yml is read-only");

        HostedInstance i = service.provision(new ProvisionRequest("acme", "acme", null));

        assertThat(i.getStatus()).isEqualTo("FAILED");
        assertThat(i.getFailedStage()).isEqualTo(ProvisionStage.INGRESS.name());
        // The port was persisted before the first ingress write, so a resume reuses it.
        assertThat(i.getPort()).isEqualTo(8091);
        assertThat(i.getJwtSecret()).isNotBlank();
        assertThat(instances).hasSize(1);
        assertThat(calls).doesNotContain("reloadCloudflared");
        assertThat(calls).noneMatch(c -> c.startsWith("dns:create"));
        assertThat(licences).isEmpty();
    }

    @Test
    void resumeAfterFailureDoesNotDuplicateDnsOrUnitOrLicence() {
        // A provision killed part-way through FRONTEND_DNS: the unit, the ingress rule and both
        // DNS records already exist, but the row still records FRONTEND_DNS as unfinished.
        HostedInstance seeded = row("acme", "acme", 8091, "FAILED", ProvisionStage.FRONTEND_DNS.name());
        seeded.setJwtSecret("SEEDED-SECRET");
        seeded.setId(7L);
        instances.add(seeded);
        cloudflare.dns.put("acme-api." + DOMAIN, "rec-api");
        cloudflare.dns.put("acme." + DOMAIN, "rec-front");
        ops.ingress = IngressEditor.addRule(LIVE_INGRESS, "acme-api." + DOMAIN, 8091);

        HostedInstance i = service.provision(new ProvisionRequest("acme", "acme", null));

        assertThat(i).isSameAs(seeded);
        assertThat(i.getStatus()).isEqualTo("ACTIVE");
        assertThat(i.getFailedStage()).isNull();
        assertThat(i.getPort()).isEqualTo(8091);
        assertThat(i.getJwtSecret()).isEqualTo("SEEDED-SECRET");
        assertThat(i.getDnsRecordId()).isEqualTo("rec-front");

        assertThat(calls).noneMatch(c -> c.startsWith("dns:create"));
        assertThat(calls).noneMatch(c -> c.startsWith("writeUnit"));
        assertThat(calls).noneMatch(c -> c.startsWith("mkdir"));
        assertThat(calls).doesNotContain("readIngress", "writeIngress", "reloadCloudflared");
        assertThat(calls).contains("pages:attach:acme." + DOMAIN);
        assertThat(licences).hasSize(1);
        assertThat(instances).hasSize(1);
    }

    @Test
    void licenceIsReusedNotDuplicatedForSameMachineAndClient() {
        License existing = new License();
        existing.setId(7L);
        existing.setClientCode("acme");
        existing.setMachineId(MACHINE_ID);
        existing.setStatus("ACTIVE");
        existing.setLabel("hosted-store");
        existing.setLicenseKey("OLD-KEY");
        licences.add(existing);

        service.provision(new ProvisionRequest("acme", "acme", null));

        // Same row refreshed in place — generating a second ACTIVE licence for one
        // (machineId, clientCode) pair is what the admin's own generate path 409s on.
        assertThat(licences).hasSize(1);
        assertThat(licences.get(0)).isSameAs(existing);
        assertThat(existing.getId()).isEqualTo(7L);
        assertThat(existing.getLabel()).isEqualTo("hosted-store");
        assertThat(existing.getLicenseKey()).isEqualTo(MINTED_KEY);
        assertThat(ops.activatedKeys).containsExactly(MINTED_KEY);
    }

    @Test
    void pagesDomainThatNeverActivatesTimesOutAsFailed() {
        cloudflare.statusOverride = "pending";

        HostedInstance i = service.provision(new ProvisionRequest("acme", "acme", null));

        assertThat(i.getStatus()).isEqualTo("FAILED");
        assertThat(i.getFailedStage()).isEqualTo(ProvisionStage.PAGES_ACTIVE.name());
        // Bounded, not a loop: exactly the attempt budget, counted after the attach (whose own
        // "is it already attached?" check reads the status once too).
        int attach = calls.indexOf("pages:attach:acme." + DOMAIN);
        long polls = calls.subList(attach + 1, calls.size()).stream()
                .filter(c -> c.equals("pages:status:acme." + DOMAIN)).count();
        assertThat(polls).isEqualTo(HostedStoreProvisioningService.PAGES_POLL_ATTEMPTS);
        // Activation measured ~105 s on the real account, so the budget has to clear 150 s.
        assertThat(HostedStoreProvisioningService.PAGES_POLL_ATTEMPTS
                * HostedStoreProvisioningService.PAGES_POLL_DELAY_MS).isGreaterThanOrEqualTo(150_000L);
        assertThat(licences).isEmpty();
        assertThat(ops.activatedKeys).isEmpty();
    }

    @Test
    void provisioningTwiceForOneClientReportsTheExistingInstance() {
        HostedInstance existing = row("acme", "acme", 8091, "ACTIVE", null);
        existing.setId(3L);
        instances.add(existing);

        HostedInstance i = service.provision(new ProvisionRequest("acme", "acme", null));

        assertThat(i).isSameAs(existing);
        assertThat(instances).hasSize(1);
        assertThat(calls).isEmpty();
        verify(hostedRepo, never()).save(any(HostedInstance.class));
        verify(licenseKeyService, never()).generateLicense(anyString(), any(), anyString(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HostedInstance row(String clientCode, String subdomain, int port, String status, String failedStage) {
        HostedInstance h = new HostedInstance();
        h.setClientCode(clientCode);
        h.setSubdomain(subdomain);
        h.setPort(port);
        h.setServiceName("daman-store-" + clientCode);
        h.setDataDir("/root/stores/" + clientCode);
        h.setApiHostname(subdomain + "-api." + DOMAIN);
        h.setFrontendHostname(subdomain + "." + DOMAIN);
        h.setStatus(status);
        h.setFailedStage(failedStage);
        return h;
    }

    /** Records every OS-touching call instead of performing it. */
    private final class FakeServerOps implements ServerOps {
        long availableMb = 2214;
        boolean healthy = true;
        Optional<String> machineId = Optional.of(MACHINE_ID);
        String ingress = LIVE_INGRESS;
        RuntimeException failWriteIngress;
        final Map<String, String> units = new LinkedHashMap<>();
        final List<String> activatedKeys = new ArrayList<>();

        @Override public void mkdir(String path) {
            calls.add("mkdir:" + path);
        }

        @Override public void writeUnit(String serviceName, String unitText) {
            calls.add("writeUnit:" + serviceName);
            units.put(serviceName, unitText);
        }

        @Override public void daemonReloadAndStart(String serviceName) {
            calls.add("start:" + serviceName);
        }

        @Override public void restart(String serviceName) {
            calls.add("restart:" + serviceName);
        }

        @Override public String readIngress() {
            calls.add("readIngress");
            return ingress;
        }

        @Override public void writeIngress(String yaml) {
            calls.add("writeIngress");
            if (failWriteIngress != null) throw failWriteIngress;
            ingress = yaml;
        }

        @Override public void reloadCloudflared() {
            calls.add("reloadCloudflared");
        }

        @Override public Optional<String> probeMachineId(int port) {
            calls.add("probeMachineId:" + port);
            return machineId;
        }

        @Override public boolean health(int port) {
            calls.add("health:" + port);
            return healthy;
        }

        @Override public void activateLicence(int port, String licenseKey) {
            calls.add("activateLicence:" + port);
            activatedKeys.add(licenseKey);
        }

        @Override public long availableMemoryMb() {
            calls.add("availableMemoryMb");
            return availableMb;
        }
    }

    /**
     * A {@link CloudflareClient} whose HTTP seam fails the test if it is ever reached, so no
     * case here can touch the real account. Mimics the API's own refusals: a duplicate CNAME
     * or a re-attached Pages domain is an error, and an unattached domain has no status.
     */
    private final class FakeCloudflare extends CloudflareClient {
        final Map<String, String> dns = new LinkedHashMap<>();
        final Map<String, String> pagesDomains = new LinkedHashMap<>();
        String statusOverride;
        private int nextId = 1;

        FakeCloudflare() {
            super(new ObjectMapper(), "acct-id", "zone-id",
                    (method, path, body) -> {
                        throw new AssertionError("no test may make a Cloudflare HTTP call: "
                                + method + " " + path);
                    });
        }

        @Override public String createProxiedCname(String hostname, String target) {
            calls.add("dns:create:" + hostname + "->" + target);
            if (dns.containsKey(hostname)) {
                throw new CloudflareException("Cloudflare error: record already exists " + hostname);
            }
            String id = "rec-" + (nextId++);
            dns.put(hostname, id);
            return id;
        }

        @Override public Optional<String> findDnsRecordId(String hostname) {
            calls.add("dns:find:" + hostname);
            return Optional.ofNullable(dns.get(hostname));
        }

        @Override public void deleteDnsRecord(String recordId) {
            calls.add("dns:delete:" + recordId);
            dns.values().remove(recordId);
        }

        @Override public void attachPagesDomain(String project, String hostname) {
            calls.add("pages:attach:" + hostname);
            if (pagesDomains.containsKey(hostname)) {
                throw new CloudflareException("Cloudflare error: domain already attached " + hostname);
            }
            pagesDomains.put(hostname, "pending");
        }

        @Override public String pagesDomainStatus(String project, String hostname) {
            calls.add("pages:status:" + hostname);
            if (!pagesDomains.containsKey(hostname)) {
                throw new CloudflareException("Cloudflare error on GET domains: [8000007] not found");
            }
            return statusOverride != null ? statusOverride : "active";
        }

        @Override public void detachPagesDomain(String project, String hostname) {
            calls.add("pages:detach:" + hostname);
            pagesDomains.remove(hostname);
        }
    }
}
