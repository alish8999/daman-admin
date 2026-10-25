package com.daman.admin.service;

import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.HostedInstance;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provisions one hosted ("online") store end to end: data directory, systemd unit, cloudflared
 * ingress rule, both DNS records, the shared Pages project's custom domain, and the store's own
 * licence — in the fixed order of {@link ProvisionStage}.
 *
 * <p><b>Resumable, never transactional.</b> The stage about to be attempted is persisted in
 * {@code HostedInstance.failedStage} before it runs. A stage that fails leaves a {@code FAILED}
 * row recording it and <b>returns</b> that row; nothing is torn down, because unwinding a
 * half-created store across systemd, a shared root-owned config file, DNS and a minted licence
 * has more ways to go wrong than leaving a clearly-marked row for a human. Calling
 * {@link #provision} again resumes from the recorded stage, skipping everything before it, and
 * every stage from {@link ProvisionStage#ALLOCATE} on is individually idempotent so re-running
 * the one that failed cannot duplicate its effect.
 *
 * <p>Refusals are different from failures: a request that is rejected up front (unknown client,
 * illegal or reserved subdomain, no RAM headroom) <b>throws</b> and persists nothing at all.
 *
 * <p><b>{@link #provision} must never be made {@code @Transactional}.</b> Each stage marker is
 * committed by its own {@code save} so that a crash — not just a caught exception — leaves a row
 * saying how far it got. One surrounding transaction would roll every marker back and the resume
 * would have nothing to read.
 *
 * <p>This service never calls {@link IngressEditor#removeRule} — it only ever adds its own
 * {@code <sub>-api} hostname. Removing a rule would be one typo away from dropping
 * {@code admin-api} or {@code viora-api} from the tunnel, so teardown stays a manual SSH task.
 *
 * <p>Not safe against two concurrent provisions of the same client code (the owner clicks one
 * button); the registry's unique constraints on {@code clientCode} and {@code subdomain} are the
 * backstop if that ever happens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostedStoreProvisioningService {

    /**
     * Bounded {@link ProvisionStage#PAGES_ACTIVE} poll: 40 × 5 s = 200 s. Activation measured
     * ~105 s against the real Cloudflare account, so the budget has to clear 150 s — and it has
     * to be a budget, because a domain whose DNS is wrong never activates at all.
     */
    static final int PAGES_POLL_ATTEMPTS = 40;
    static final long PAGES_POLL_DELAY_MS = 5_000L;

    /** Bounded wait for a freshly started store to answer {@code /api/health}: 30 × 2 s = 60 s. */
    private static final int START_HEALTH_ATTEMPTS = 30;
    private static final long START_HEALTH_DELAY_MS = 2_000L;

    /** Must stay equal to the {@code -Xmx} in {@link StoreUnitRenderer}'s unit template. */
    private static final int STORE_HEAP_MB = 768;

    /** Free RAM that must remain after another store's heap (spec §7's suggestion). */
    private static final int RAM_MARGIN_MB = 300;

    private static final String SERVICE_PREFIX = "daman-store-";

    private static final String STATUS_PROVISIONING = "PROVISIONING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String LICENCE_ACTIVE = "ACTIVE";
    private static final String LICENCE_LABEL = "hosted-store";

    /**
     * Client codes reach us from the {@code ClientConfig} table, which validates nothing — real
     * ones include {@code abu_ammar}, {@code Bistro-cafe}, {@code Hadid} and {@code 2-brothers}.
     * This whitelist is what keeps a code (and the service name derived from it) out of trouble:
     * no {@code %} (systemd expands specifiers inside unit files), no CR/LF (either would inject
     * extra directives into a rendered unit or the ingress YAML), no path separators, no spaces.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** Characters checked by name so the reason survives any future loosening of the pattern. */
    private static final char[] FORBIDDEN_IN_IDENTIFIERS = {'%', '\r', '\n'};

    /** The waiting seam, so tests exercise a full attempt budget without taking the wall time. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * One provisioning request. {@code subdomain} is validated, never trusted, and must be the
     * same on a resume as it was on the first attempt. {@code expiresAt} is an ISO date or null
     * (perpetual) and is authoritative: it is what the minted licence carries, so a resume has to
     * pass the same value it started with.
     */
    public record ProvisionRequest(String clientCode, String subdomain, String expiresAt) {}

    private final HostedInstanceRepository hostedInstanceRepository;
    private final ClientConfigRepository clientConfigRepository;
    private final ClientConfigService clientConfigService;
    private final LicenseKeyService licenseKeyService;
    private final LicenseRepository licenseRepository;
    private final CloudflareClient cloudflare;
    private final ServerOps ops;

    @Value("${daman.hosted.domain:damansoft.com}")
    private String hostedDomain;

    @Value("${daman.hosted.pages-project:daman-store}")
    private String pagesProject;

    @Value("${daman.cloudflare.tunnel-id:}")
    private String tunnelId;

    @Value("${daman.cloudflare.account-id:}")
    private String cloudflareAccountId;

    @Value("${daman.cloudflare.zone-id:}")
    private String cloudflareZoneId;

    private Sleeper sleeper = Thread::sleep;

    /**
     * Provisions (or resumes provisioning of) the hosted store for {@code request.clientCode()}.
     *
     * @return the registry row — {@code ACTIVE} when the store is live, {@code FAILED} with
     *         {@code failedStage} set when a stage failed, or the untouched existing row when the
     *         client is already provisioned.
     * @throws IllegalArgumentException for a refused request (unknown client, illegal/reserved/
     *         taken subdomain, unparseable expiry) — nothing is persisted.
     * @throws IllegalStateException when the server cannot host another store, or Cloudflare is
     *         not configured — again, nothing is persisted.
     */
    public HostedInstance provision(ProvisionRequest request) {
        // ── VALIDATE ─────────────────────────────────────────────────────────
        // Never recorded in failedStage: there is no row yet, so a failure here is a refusal.
        String clientCode = request.clientCode() == null ? "" : request.clientCode().trim();
        requireSafeIdentifier("clientCode", clientCode);
        requireSafeIdentifier("serviceName", SERVICE_PREFIX + clientCode);
        clientConfigRepository.findByClientCode(clientCode).orElseThrow(
                () -> new IllegalArgumentException("Client not found: " + clientCode));
        LocalDate expiresAt = parseExpiry(request.expiresAt());

        HostedInstance existing = hostedInstanceRepository.findByClientCode(clientCode).orElse(null);
        if (existing != null && STATUS_ACTIVE.equals(existing.getStatus())) {
            log.info("Hosted store '{}' already exists on {} (port {}) — reporting it unchanged",
                    clientCode, existing.getFrontendHostname(), existing.getPort());
            return existing;
        }

        String subdomain = resolveSubdomain(request.subdomain(), existing);
        requireCloudflareConfigured();

        HostedInstance instance;
        ProvisionStage resumeFrom;
        if (existing == null) {
            // Only on a fresh provision: a resumed store's JVM is already running, so its heap is
            // already out of availableMemoryMb() and re-checking would double-count it and refuse
            // the resume for ever.
            requireCapacity();
            instance = newRow(clientCode, subdomain);
            resumeFrom = ProvisionStage.ALLOCATE;
        } else {
            instance = existing;
            resumeFrom = resumeStageOf(existing);
            log.info("Resuming provisioning of hosted store '{}' at stage {}", clientCode, resumeFrom);
        }
        // Checks the value actually used, which on a resume is whatever the row has stored.
        requireSafeIdentifier("serviceName", instance.getServiceName());

        // ── ALLOCATE .. VERIFY ───────────────────────────────────────────────
        for (ProvisionStage stage : ProvisionStage.values()) {
            if (stage == ProvisionStage.VALIDATE || stage.ordinal() < resumeFrom.ordinal()) {
                continue;
            }
            try {
                instance.setStatus(STATUS_PROVISIONING);
                instance.setFailedStage(stage.name());
                instance = hostedInstanceRepository.save(instance);
                run(stage, instance, expiresAt);
                instance = hostedInstanceRepository.save(instance);
            } catch (RuntimeException e) {
                log.error("Provisioning hosted store '{}' failed at stage {}: {}",
                        clientCode, stage, e.getMessage(), e);
                instance.setStatus(STATUS_FAILED);
                instance.setFailedStage(stage.name());
                return hostedInstanceRepository.save(instance);
            }
        }

        instance.setStatus(STATUS_ACTIVE);
        instance.setFailedStage(null);
        log.info("Hosted store '{}' is live on {} (api {}, port {})", clientCode,
                instance.getFrontendHostname(), instance.getApiHostname(), instance.getPort());
        return hostedInstanceRepository.save(instance);
    }

    private void run(ProvisionStage stage, HostedInstance i, LocalDate expiresAt) {
        switch (stage) {
            case ALLOCATE -> allocate(i);
            case DATA_DIR -> ops.mkdir(i.getDataDir());
            case UNIT -> ops.writeUnit(i.getServiceName(), StoreUnitRenderer.render(i));
            case START -> start(i);
            case INGRESS -> addIngressRule(i);
            case API_DNS -> ensureDnsRecord(i.getApiHostname(), tunnelId + ".cfargotunnel.com");
            case FRONTEND_DNS -> i.setDnsRecordId(
                    ensureDnsRecord(i.getFrontendHostname(), pagesProject + ".pages.dev"));
            case PAGES_ATTACH -> attachPagesDomain(i);
            case PAGES_ACTIVE -> awaitPagesDomainActive(i);
            case LICENCE -> licence(i, expiresAt);
            case VERIFY -> verify(i);
            case VALIDATE -> throw new IllegalStateException("VALIDATE runs before the stage loop");
        }
    }

    // ── stages ───────────────────────────────────────────────────────────────

    /**
     * Fills in the port and the JWT secret — and only if they are missing. A resume MUST keep the
     * port the ingress file was written with: {@link IngressEditor#addRule} deliberately refuses
     * the same hostname on a different port rather than silently remapping a shared root-owned
     * file, so a freshly allocated port would wedge the resume at {@link ProvisionStage#INGRESS}
     * for good. The secret is kept for the same reason in reverse — regenerating it would log the
     * store's users out and invalidate the unit that is already running.
     */
    private void allocate(HostedInstance i) {
        if (i.getPort() <= 0) {
            Set<Integer> taken = new HashSet<>();
            hostedInstanceRepository.findAll().forEach(row -> taken.add(row.getPort()));
            i.setPort(HostedCapacity.nextFreePort(taken));
            log.info("Allocated port {} to hosted store '{}'", i.getPort(), i.getClientCode());
        }
        if (i.getJwtSecret() == null || i.getJwtSecret().isBlank()) {
            i.setJwtSecret(StoreUnitRenderer.newJwtSecret());
        }
    }

    /** Starts the unit, then waits (bounded) for the store to answer, so a dead unit fails here. */
    private void start(HostedInstance i) {
        ops.daemonReloadAndStart(i.getServiceName());
        for (int attempt = 1; attempt <= START_HEALTH_ATTEMPTS; attempt++) {
            if (ops.health(i.getPort())) {
                return;
            }
            pause(START_HEALTH_DELAY_MS, attempt, START_HEALTH_ATTEMPTS);
        }
        throw new IllegalStateException(i.getServiceName() + " did not answer /api/health on port "
                + i.getPort() + " within " + seconds(START_HEALTH_ATTEMPTS, START_HEALTH_DELAY_MS) + "s");
    }

    /**
     * Adds this store's API hostname to the shared cloudflared config. Idempotent through
     * {@link IngressEditor#addRule}, which returns the file unchanged when the identical rule is
     * already there — in which case cloudflared is already serving it and must not be signalled.
     */
    private void addIngressRule(HostedInstance i) {
        String current = ops.readIngress();
        String updated = IngressEditor.addRule(current, i.getApiHostname(), i.getPort());
        if (updated.equals(current)) {
            log.info("Ingress rule for {} is already present", i.getApiHostname());
            return;
        }
        ops.writeIngress(updated);
        ops.reloadCloudflared();
    }

    /**
     * The record id for {@code hostname}, creating the proxied CNAME only if the zone does not
     * already have one. The lookup is what makes a resume safe: a run killed after
     * {@code createProxiedCname} returned but before its stage was persisted would otherwise add a
     * second CNAME for the same hostname. An adopted record is not checked against {@code target}
     * (the API does not return it here) — a pre-existing record pointing somewhere else surfaces
     * as the Pages domain never activating, which is bounded and loud.
     */
    private String ensureDnsRecord(String hostname, String target) {
        Optional<String> existing = cloudflare.findDnsRecordId(hostname);
        if (existing.isPresent()) {
            log.info("DNS record for {} already exists ({}) — reusing it", hostname, existing.get());
            return existing.get();
        }
        String recordId = cloudflare.createProxiedCname(hostname, target);
        log.info("Created proxied CNAME {} -> {} ({})", hostname, target, recordId);
        return recordId;
    }

    private void attachPagesDomain(HostedInstance i) {
        if (pagesDomainAttached(i.getFrontendHostname())) {
            log.info("Pages domain {} is already attached to project {}",
                    i.getFrontendHostname(), pagesProject);
            return;
        }
        cloudflare.attachPagesDomain(pagesProject, i.getFrontendHostname());
    }

    /**
     * Cloudflare answers a failed envelope for a domain the project does not have, so an exception
     * is read here as "not attached" and nothing else. A transient API failure therefore looks the
     * same, and the attach that follows then fails loudly as {@link ProvisionStage#PAGES_ATTACH}
     * (Cloudflare refuses a duplicate), which a resume handles — rather than quietly skipping a
     * domain that was never actually attached.
     */
    private boolean pagesDomainAttached(String hostname) {
        try {
            cloudflare.pagesDomainStatus(pagesProject, hostname);
            return true;
        } catch (CloudflareClient.CloudflareException e) {
            return false;
        }
    }

    private void awaitPagesDomainActive(HostedInstance i) {
        String hostname = i.getFrontendHostname();
        String status = null;
        for (int attempt = 1; attempt <= PAGES_POLL_ATTEMPTS; attempt++) {
            status = cloudflare.pagesDomainStatus(pagesProject, hostname);
            if ("active".equalsIgnoreCase(status)) {
                log.info("Pages domain {} is active after {} poll(s)", hostname, attempt);
                return;
            }
            pause(PAGES_POLL_DELAY_MS, attempt, PAGES_POLL_ATTEMPTS);
        }
        throw new IllegalStateException("Pages domain " + hostname + " did not become active within "
                + seconds(PAGES_POLL_ATTEMPTS, PAGES_POLL_DELAY_MS) + "s (last status: " + status + ")");
    }

    /**
     * Mints — or reuses — the store's v2 licence and activates it on the store itself.
     *
     * <p>The machine id comes from the store's own backend, which is the only thing that can
     * compute it. Both stores on this box report the same value and that is fine: a licence is
     * keyed on the {@code (machineId, clientCode)} pair. An existing {@code ACTIVE} row for that
     * pair is refreshed in place rather than duplicated — generating a second one is exactly what
     * {@code POST /api/licenses/generate} refuses with a 409 (see {@code DevRunService}).
     */
    private void licence(HostedInstance i, LocalDate expiresAt) {
        String machineId = ops.probeMachineId(i.getPort())
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .orElseThrow(() -> new IllegalStateException("the store on port " + i.getPort()
                        + " did not report a machineId — is " + i.getServiceName() + " running?"));

        ClientConfig config = clientConfigRepository.findByClientCode(i.getClientCode())
                .orElseThrow(() -> new IllegalStateException("Client not found: " + i.getClientCode()));
        ClientConfigService.LicenseEntitlements entitlements =
                clientConfigService.licenseEntitlementsFor(i.getClientCode());

        String licenseKey = licenseKeyService.generateLicense(
                machineId, config.getAppName(), i.getClientCode(),
                expiresAt == null ? null : expiresAt.toString(),
                entitlements.baseCurrency(), entitlements.features(),
                entitlements.colorPrimary(), entitlements.colorSecondary(),
                entitlements.adminUsername(), entitlements.adminPassword(), entitlements.profile());

        License licence = licenseRepository
                .findByMachineIdAndClientCodeAndStatus(machineId, i.getClientCode(), LICENCE_ACTIVE)
                .orElseGet(() -> {
                    License fresh = new License();
                    fresh.setClientCode(i.getClientCode());
                    fresh.setMachineId(machineId);
                    fresh.setStatus(LICENCE_ACTIVE);
                    fresh.setLabel(LICENCE_LABEL);
                    return fresh;
                });
        licence.setLicenseKey(licenseKey);
        licence.setClientName(config.getAppName());
        licence.setExpiresAt(expiresAt);
        licenseRepository.save(licence);

        ops.activateLicence(i.getPort(), licenseKey);
    }

    private void verify(HostedInstance i) {
        if (!ops.health(i.getPort())) {
            throw new IllegalStateException(i.getServiceName() + " is not answering /api/health on port "
                    + i.getPort() + " after its licence was activated");
        }
    }

    // ── validation ───────────────────────────────────────────────────────────

    private String resolveSubdomain(String requested, HostedInstance existing) {
        String subdomain = requested == null ? "" : requested.trim();
        if (existing != null) {
            // The row owns its subdomain: the unit, the ingress rule, both DNS records and the
            // Pages domain were all built from the original, so a resume never renames a store.
            if (!subdomain.isEmpty() && !subdomain.equals(existing.getSubdomain())) {
                throw new IllegalArgumentException("Hosted store '" + existing.getClientCode()
                        + "' is already half-provisioned as subdomain '" + existing.getSubdomain()
                        + "'; refusing to change it to '" + subdomain + "'");
            }
            subdomain = existing.getSubdomain();
        }
        if (subdomain.isEmpty()) {
            throw new IllegalArgumentException(
                    "A subdomain is required (SubdomainRules.sanitise suggests one from a client code)");
        }
        // sanitise() only ever returns a SUGGESTION — it caps length and yields "" for a code with
        // no usable ASCII — so whatever arrives here is validated from scratch.
        if (!SubdomainRules.isValid(subdomain)) {
            throw new IllegalArgumentException("Invalid subdomain '" + subdomain + "': lowercase letters, "
                    + "digits and inner hyphens only, 1-32 characters (no underscores, no edge hyphens)");
        }
        if (SubdomainRules.isReserved(subdomain)) {
            throw new IllegalArgumentException("The subdomain '" + subdomain
                    + "' is reserved for Daman's own infrastructure");
        }
        if (existing == null && hostedInstanceRepository.existsBySubdomain(subdomain)) {
            throw new IllegalArgumentException("The subdomain '" + subdomain
                    + "' is already taken by another hosted store");
        }
        return subdomain;
    }

    private void requireCapacity() {
        long availableMb = ops.availableMemoryMb();
        if (!HostedCapacity.hasRoomFor(availableMb, STORE_HEAP_MB, RAM_MARGIN_MB)) {
            throw new IllegalStateException("Not enough RAM for another hosted store: " + availableMb
                    + " MB available, a store needs " + STORE_HEAP_MB + " MB of heap and " + RAM_MARGIN_MB
                    + " MB must stay free. The fix is a bigger server, not a smaller margin.");
        }
    }

    /**
     * {@link CloudflareClient} concatenates the account and zone ids into its request paths
     * without checking them, so a blank one would POST to {@code /zones//dns_records} and get a
     * confusing API error instead of failing here with the name of the missing setting.
     */
    private void requireCloudflareConfigured() {
        requireConfigured("daman.cloudflare.zone-id", cloudflareZoneId);
        requireConfigured("daman.cloudflare.account-id", cloudflareAccountId);
        requireConfigured("daman.cloudflare.tunnel-id", tunnelId);
        requireConfigured("daman.hosted.pages-project", pagesProject);
        requireConfigured("daman.hosted.domain", hostedDomain);
    }

    private static void requireConfigured(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is not configured; hosted stores cannot be "
                    + "provisioned without it");
        }
    }

    private static void requireSafeIdentifier(String what, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        for (char forbidden : FORBIDDEN_IN_IDENTIFIERS) {
            if (value.indexOf(forbidden) >= 0) {
                throw new IllegalArgumentException("Unsafe " + what + " '" + value + "': it contains "
                        + describe(forbidden));
            }
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe " + what + " '" + value + "': letters, digits, "
                    + "'_' and '-' only, starting with a letter or digit");
        }
    }

    private static String describe(char c) {
        return switch (c) {
            case '%' -> "'%', which systemd expands as a specifier inside a unit file";
            case '\r' -> "a carriage return";
            case '\n' -> "a newline";
            default -> "'" + c + "'";
        };
    }

    private static LocalDate parseExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(expiresAt.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an ISO date (yyyy-MM-dd), got '"
                    + expiresAt + "'");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HostedInstance newRow(String clientCode, String subdomain) {
        HostedInstance i = new HostedInstance();
        i.setClientCode(clientCode);
        i.setSubdomain(subdomain);
        i.setServiceName(SERVICE_PREFIX + clientCode);
        // One derivation, shared with the unit renderer, so the registry and the running unit can
        // never disagree about where a store's data lives. Nothing renders a unit FROM dataDir:
        // viora's legacy row says /root/.daman/viora and must stay truthful about that.
        i.setDataDir(StoreUnitRenderer.dataDir(clientCode));
        i.setApiHostname(subdomain + "-api." + hostedDomain);
        i.setFrontendHostname(subdomain + "." + hostedDomain);
        i.setStatus(STATUS_PROVISIONING);
        return i;
    }

    /**
     * The stage a half-provisioned row resumes from: the one it recorded as unfinished. An absent
     * or unrecognised value restarts at {@link ProvisionStage#ALLOCATE}, which is safe because
     * every stage from there on is idempotent.
     */
    private static ProvisionStage resumeStageOf(HostedInstance instance) {
        String recorded = instance.getFailedStage();
        if (recorded == null || recorded.isBlank()) {
            return ProvisionStage.ALLOCATE;
        }
        try {
            ProvisionStage stage = ProvisionStage.valueOf(recorded);
            return stage == ProvisionStage.VALIDATE ? ProvisionStage.ALLOCATE : stage;
        } catch (IllegalArgumentException e) {
            log.warn("Hosted store '{}' records an unknown stage '{}' — restarting from {}",
                    instance.getClientCode(), recorded, ProvisionStage.ALLOCATE);
            return ProvisionStage.ALLOCATE;
        }
    }

    /** Waits between polls, but never after the final attempt. */
    private void pause(long millis, int attempt, int attempts) {
        if (attempt >= attempts) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the previous step", e);
        }
    }

    private static long seconds(int attempts, long delayMs) {
        return attempts * delayMs / 1000;
    }
}
