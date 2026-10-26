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
import java.util.Locale;
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
 * <p><b>Split in two halves on purpose.</b> {@link #prepare} is synchronous and decides every
 * refusal before anything is created; {@link #runStages} is the long part and runs on
 * {@link HostedStoreProvisionLauncher}'s per-client thread, because the whole sequence takes
 * 125-150 s and the admin API is reached through a Cloudflare tunnel that answers 524 after 100 s
 * of origin silence. {@link #provision} still runs both back to back and is what the tests drive.
 *
 * <p>Concurrency: {@link HostedStoreProvisionLauncher} rejects a second <i>in-flight</i> run for
 * one client code and serialises anything that does get through on a single thread per client,
 * because two runs reaching {@link #licence} together would both see no {@code ACTIVE} licence row
 * and insert one each. The registry's unique constraints on {@code clientCode} and
 * {@code subdomain} are the backstop under that.
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

    /**
     * Bounded wait for the store to answer through its OWN public hostname: 6 × 5 s = 30 s. A grace
     * period for Cloudflare's edge, not a propagation wait — see {@link #requirePubliclyReachable}.
     */
    private static final int PUBLIC_HEALTH_ATTEMPTS = 6;
    private static final long PUBLIC_HEALTH_DELAY_MS = 5_000L;

    /** Free RAM that must remain after another store's heap (spec §7's suggestion). */
    private static final int RAM_MARGIN_MB = HostedCapacity.RAM_MARGIN_MB;

    /**
     * RAM reserved for everything on this box that is <b>not</b> a hosted store: the admin
     * backend's own JVM, plus the OS, sshd and cloudflared.
     *
     * <p>Chosen from spec §7's measurements (total 3814 MB; admin backend 341 MB RSS against an
     * {@code -Xmx1536m} ceiling it has never approached; ~510 MB for the OS and the rest). 1 GB
     * covers the measured 851 MB with room for the admin JVM to grow, without reserving its whole
     * ceiling — reserving 1536 MB would cap this box at two stores, and §7's own conclusion is
     * "roughly three". With this value the guard allows a 2nd and a 3rd store and refuses a 4th,
     * which is exactly that conclusion.
     */
    private static final int NON_STORE_RESERVE_MB = HostedCapacity.NON_STORE_RESERVE_MB;

    private static final String SERVICE_PREFIX = "daman-store-";

    /** How many already-listening ports to step over before giving up (see {@link #allocatePort}). */
    private static final int MAX_PORT_PROBES = 50;

    private static final String STATUS_PROVISIONING = "PROVISIONING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SUSPENDED = "SUSPENDED";
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
     * A request refused because of its own input: unknown client, illegal/reserved/taken subdomain,
     * unparseable expiry, unsafe identifier. Nothing is persisted. The controller answers 400.
     *
     * <p>It extends {@link IllegalArgumentException} so it still reads as one, but the controller
     * catches <b>this</b> type: a bare {@code catch (IllegalArgumentException | IllegalStateException)}
     * is wide enough to swallow an incidental Spring or JPA state exception and echo its internal
     * message straight back to the caller.
     */
    public static class RefusedException extends IllegalArgumentException {
        public RefusedException(String message) {
            super(message);
        }
    }

    /**
     * The server cannot provision another store right now — no RAM headroom, or Cloudflare is not
     * configured. Nothing is persisted. The controller answers <b>503</b>, not 400: the request is
     * fine, the server is not, and retrying it unchanged later is the right thing to do.
     */
    public static class UnavailableException extends IllegalStateException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    /**
     * What {@link #prepare}'s synchronous half decided, handed to {@link #runStages}' long half.
     *
     * @param instance      the persisted registry row — already {@code PROVISIONING}, so the caller
     *                      can return it and a poller can watch it
     * @param resumeFrom    the first stage to run (always {@code ALLOCATE} for a fresh store)
     * @param expiresAt     the licence expiry this run must mint, parsed once
     * @param alreadyActive true when the client already had an {@code ACTIVE} store: nothing was
     *                      touched and nothing will be
     */
    public record PreparedProvision(HostedInstance instance, ProvisionStage resumeFrom,
                                    LocalDate expiresAt, boolean alreadyActive) {}

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
     * {@link #prepare} then {@link #runStages}, back to back on the calling thread.
     *
     * <p>Production goes through {@link HostedStoreProvisionLauncher} instead, which runs the two
     * halves on different threads so the HTTP response is not held open for 125-150 s. This method
     * is the whole sequence in one call, which is what the tests drive.
     *
     * @return the registry row — {@code ACTIVE} when the store is live, {@code FAILED} with
     *         {@code failedStage} set when a stage failed, or the untouched existing row when the
     *         client is already provisioned.
     * @throws RefusedException for a refused request (unknown client, illegal/reserved/taken
     *         subdomain, unparseable expiry) — nothing is persisted.
     * @throws UnavailableException when the server cannot host another store, or Cloudflare is not
     *         configured — again, nothing is persisted.
     */
    public HostedInstance provision(ProvisionRequest request) {
        return runStages(prepare(request));
    }

    /**
     * The synchronous half: every refusal, and the registry row the long half will walk.
     *
     * <p>Fast by construction — a few queries, one {@code /proc/meminfo} read and one
     * {@code save} — so it is safe to do inside an HTTP request. It runs no stage, touches no
     * systemd unit, writes no DNS record and contacts Cloudflare not at all.
     *
     * @throws RefusedException     the request's own input is wrong (400)
     * @throws UnavailableException this server cannot provision right now (503)
     */
    public PreparedProvision prepare(ProvisionRequest request) {
        // ── VALIDATE ─────────────────────────────────────────────────────────
        // Never recorded in failedStage: there is no row yet, so a failure here is a refusal.
        String clientCode = request.clientCode() == null ? "" : request.clientCode().trim();
        requireSafeIdentifier("clientCode", clientCode);
        requireSafeIdentifier("serviceName", SERVICE_PREFIX + clientCode);
        clientConfigRepository.findByClientCode(clientCode).orElseThrow(
                () -> new RefusedException("Client not found: " + clientCode));
        LocalDate expiresAt = parseExpiry(request.expiresAt());

        HostedInstance existing = hostedInstanceRepository.findByClientCode(clientCode).orElse(null);
        if (existing != null && STATUS_ACTIVE.equals(existing.getStatus())) {
            log.info("Hosted store '{}' already exists on {} (port {}) — reporting it unchanged",
                    clientCode, existing.getFrontendHostname(), existing.getPort());
            return new PreparedProvision(existing, ProvisionStage.VERIFY, expiresAt, true);
        }

        // A SUSPENDED row has no failedStage, so it would fall through to resumeStageOf() == ALLOCATE
        // and the walk would re-run UNIT + START, and START is `enable --now`: Create would silently
        // put a suspended (non-paying) customer back online. Only Resume may do that.
        if (existing != null && STATUS_SUSPENDED.equals(existing.getStatus())) {
            throw new RefusedException("Hosted store '" + clientCode + "' is suspended; "
                    + "Create will not bring it back online. Use Resume to re-enable it.");
        }

        if (existing == null) {
            requireUnreservedClientCode(clientCode);
        }
        String subdomain = resolveSubdomain(request.subdomain(), existing);
        requireCloudflareConfigured();

        HostedInstance instance;
        ProvisionStage resumeFrom;
        if (existing == null) {
            // Only on a fresh provision: a resumed store already has a registry row, so the
            // reserve-based guard is already counting its heap and re-checking it would refuse the
            // resume for ever.
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

        // Persisted here, not in the loop: the caller returns this row immediately and the dashboard
        // polls it, so it has to exist before the long half starts. Recording the stage it is about
        // to begin also keeps a crash between the two halves resumable from the right place.
        instance.setStatus(STATUS_PROVISIONING);
        instance.setFailedStage(resumeFrom.name());
        return new PreparedProvision(hostedInstanceRepository.save(instance), resumeFrom, expiresAt, false);
    }

    /**
     * The long half: {@code ALLOCATE} .. {@code VERIFY}, 125-150 s on the happy path. Runs on
     * {@link HostedStoreProvisionLauncher}'s thread for this client code in production.
     *
     * <p>Never throws for a stage failure — it records the stage on the row and returns it, which
     * is what makes a re-run resume rather than restart.
     */
    public HostedInstance runStages(PreparedProvision prepared) {
        if (prepared.alreadyActive()) {
            return prepared.instance();
        }
        HostedInstance instance = prepared.instance();
        String clientCode = instance.getClientCode();

        for (ProvisionStage stage : ProvisionStage.values()) {
            if (stage == ProvisionStage.VALIDATE || stage.ordinal() < prepared.resumeFrom().ordinal()) {
                continue;
            }
            try {
                instance.setStatus(STATUS_PROVISIONING);
                instance.setFailedStage(stage.name());
                instance = hostedInstanceRepository.save(instance);
                run(stage, instance, prepared.expiresAt());
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
            i.setPort(allocatePort());
            log.info("Allocated port {} to hosted store '{}'", i.getPort(), i.getClientCode());
        }
        if (i.getJwtSecret() == null || i.getJwtSecret().isBlank()) {
            i.setJwtSecret(StoreUnitRenderer.newJwtSecret());
        }
    }

    /**
     * The lowest port in the band that is free in the registry <b>and</b> not already listening
     * (spec §6's cross-check). The registry alone is not enough: anything else on the box holding
     * a port would otherwise be handed out, the store would fail to bind, and it would surface a
     * minute later as a confusing {@link ProvisionStage#START} failure.
     */
    private int allocatePort() {
        Set<Integer> taken = new HashSet<>();
        hostedInstanceRepository.findAll().forEach(row -> taken.add(row.getPort()));
        for (int probe = 0; probe <= MAX_PORT_PROBES; probe++) {
            int port = HostedCapacity.nextFreePort(taken);
            if (!ops.portInUse(port)) {
                return port;
            }
            log.warn("Port {} is free in the registry but something is already listening on it", port);
            taken.add(port);
        }
        throw new IllegalStateException("No free port found in " + MAX_PORT_PROBES
                + " attempts from " + HostedCapacity.PORT_BAND_START
                + " — every candidate is already listening");
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

    /**
     * Health, then proof that the licence actually stuck. The store itself is the only authority:
     * a <i>refused</i> activation already fails loudly at {@link ProvisionStage#LICENCE}, because
     * {@code activateLicence} throws on any non-2xx and the store answers 400 when it refuses a
     * key. What this closes is the other case — activation returned 200 but the store does not
     * consider itself licensed, or considers itself licensed as a different client.
     */
    private void verify(HostedInstance i) {
        if (!ops.health(i.getPort())) {
            throw new IllegalStateException(i.getServiceName() + " is not answering /api/health on port "
                    + i.getPort() + " after its licence was activated");
        }
        ServerOps.LicenceStatus status = ops.licenceStatus(i.getPort())
                .orElseThrow(() -> new IllegalStateException(i.getServiceName()
                        + " did not report its licence status on port " + i.getPort()));
        if (!status.licensed()) {
            throw new IllegalStateException(i.getServiceName() + " still reports itself unlicensed:"
                    + " the activation was accepted but did not take");
        }
        if (!i.getClientCode().equals(status.clientCode())) {
            throw new IllegalStateException(i.getServiceName() + " reports client code '"
                    + status.clientCode() + "' but this store is '" + i.getClientCode()
                    + "' — it is running on the wrong licence");
        }
        requirePubliclyReachable(i);
    }

    /**
     * The last check, and the only one that proves a <i>customer</i> can reach this store:
     * {@code https://<sub>-api.<domain>/api/health}, through Cloudflare's edge. Every other probe
     * in the sequence talks to {@code 127.0.0.1}, so without this an {@code ACTIVE} row means only
     * "the JVM is up". Three real ways to get a healthy JVM at a dead URL, none of which anything
     * else in the sequence catches:
     *
     * <ul>
     *   <li>cloudflared not actually serving the new ingress rule after its restart (the restart
     *       is verified active, not that it routes this hostname);</li>
     *   <li>{@link #ensureDnsRecord} adopting a pre-existing {@code <sub>-api} record that points
     *       somewhere else — the Pages poll watches the <i>frontend</i> record, not this one;</li>
     *   <li>a hand-edited ingress line {@code IngressEditor}'s pattern does not recognise, leaving
     *       a duplicate rule cloudflared resolves to the wrong entry.</li>
     * </ul>
     *
     * <p>Hairpinning out to the edge and back is fine: this box has outbound access — it has just
     * made several Cloudflare API calls. Bounded and briefly retried, because the record was created
     * minutes ago ({@code PAGES_ACTIVE} alone takes ~105 s): this is a grace period for the edge,
     * not a propagation wait.
     */
    private void requirePubliclyReachable(HostedInstance i) {
        for (int attempt = 1; attempt <= PUBLIC_HEALTH_ATTEMPTS; attempt++) {
            if (ops.publicHealth(i.getApiHostname())) {
                log.info("{} answers /api/health through its own public hostname", i.getApiHostname());
                return;
            }
            pause(PUBLIC_HEALTH_DELAY_MS, attempt, PUBLIC_HEALTH_ATTEMPTS);
        }
        throw new IllegalStateException("https://" + i.getApiHostname() + "/api/health did not answer"
                + " in " + PUBLIC_HEALTH_ATTEMPTS + " attempts over ~"
                + seconds(PUBLIC_HEALTH_ATTEMPTS, PUBLIC_HEALTH_DELAY_MS)
                + "s, even though the store is healthy on 127.0.0.1:" + i.getPort()
                + ". The store is running but customers cannot reach it. Check that cloudflared"
                + " picked up the new ingress rule (journalctl -u cloudflared; 'systemctl restart"
                + " cloudflared' if it is not serving the rule), that /etc/cloudflared/config.yml has"
                + " exactly one rule for " + i.getApiHostname() + ", and that its DNS record is the"
                + " tunnel CNAME rather than an older record pointing elsewhere. Note the probe"
                + " crosses Cloudflare's edge, so an answer generated BY the edge (a 403 from a WAF"
                + " rule, say) fails this stage the same way a dead tunnel does — backend.log"
                + " records each probe's status code, which tells the two apart at a glance.");
    }

    // ── validation ───────────────────────────────────────────────────────────

    private String resolveSubdomain(String requested, HostedInstance existing) {
        String subdomain = requested == null ? "" : requested.trim();
        if (existing != null) {
            // The row owns its subdomain: the unit, the ingress rule, both DNS records and the
            // Pages domain were all built from the original, so a resume never renames a store.
            if (!subdomain.isEmpty() && !subdomain.equals(existing.getSubdomain())) {
                throw new RefusedException("Hosted store '" + existing.getClientCode()
                        + "' is already half-provisioned as subdomain '" + existing.getSubdomain()
                        + "'; refusing to change it to '" + subdomain + "'. To start over under a"
                        + " different name, delete the row first (DELETE /api/hosted-stores/"
                        + existing.getClientCode() + "; see DEPLOYMENT.md §10.5)");
            }
            subdomain = existing.getSubdomain();
        }
        if (subdomain.isEmpty()) {
            throw new RefusedException(
                    "A subdomain is required (SubdomainRules.sanitise suggests one from a client code)");
        }
        // sanitise() only ever returns a SUGGESTION — it caps length and yields "" for a code with
        // no usable ASCII — so whatever arrives here is validated from scratch.
        if (!SubdomainRules.isValid(subdomain)) {
            throw new RefusedException("Invalid subdomain '" + subdomain + "': lowercase letters, "
                    + "digits and inner hyphens only, 1-32 characters (no underscores, no edge hyphens)");
        }
        if (SubdomainRules.isReserved(subdomain)) {
            throw new RefusedException("The subdomain '" + subdomain
                    + "' is reserved for Daman's own infrastructure");
        }
        if (existing == null && hostedInstanceRepository.existsBySubdomain(subdomain)) {
            throw new RefusedException("The subdomain '" + subdomain
                    + "' is already taken by another hosted store");
        }
        return subdomain;
    }

    /**
     * {@link SubdomainRules#isReserved} guards the subdomain axis; this guards the other one. The
     * service name — and so the unit file path — comes from the client code, so provisioning a
     * client code of {@code viora} would overwrite {@code daman-store-viora.service} with the
     * wrong {@code -Duser.home}, a new JWT secret and another store's port, and the live viora
     * would come up empty on its next {@code Restart=always} cycle. The backfilled {@code ACTIVE}
     * registry row already prevents that by making {@link #prepare} return early, but "viora is
     * never touched" is the one outcome the spec marks as unacceptable, so it does not rest on a
     * single row's status.
     *
     * <p>Deliberately checks the explicit {@link SubdomainRules#RESERVED} set only, and <b>not</b>
     * the {@code -api} suffix rule that {@link SubdomainRules#isReserved} adds on top of it. That
     * asymmetry is correct, not an oversight: the suffix rule exists because {@code <sub>-api} is
     * the hostname this feature derives for every store's own API, so a <i>subdomain</i> ending
     * {@code -api} would collide with another store's API hostname. A <i>client code</i> ending
     * {@code -api} derives nothing and collides with nothing — it would only ever produce a service
     * named {@code daman-store-foo-api}, which is as free as any other. Please do not "fix" it.
     */
    private static void requireUnreservedClientCode(String clientCode) {
        if (SubdomainRules.RESERVED.contains(clientCode.toLowerCase(Locale.ROOT))) {
            throw new RefusedException("The client code '" + clientCode + "' is reserved for "
                    + "Daman's own infrastructure: provisioning it would overwrite "
                    + SERVICE_PREFIX + clientCode + ".service");
        }
    }

    /**
     * Refuses when this box has no room for another store — <b>reserving</b> each existing store's
     * heap rather than measuring what it happens to be using.
     *
     * <p>{@code MemAvailable} was the obvious input and is the wrong one: it reflects each store's
     * <i>current</i> RSS, and a freshly booted JVM sits far below its {@code -Xmx} ceiling for a
     * while. Two stores provisioned back to back would both pass that check before either heap
     * matured — which is precisely how spec §2's anti-goal ("overcommitting so an existing
     * customer's store is OOM-killed by a new one") happens. So the input is total RAM minus every
     * registry row's full heap minus {@link #NON_STORE_RESERVE_MB}, which cannot be fooled by
     * timing.
     *
     * <p>Every registry row counts, including viora's and including a {@code FAILED} one. That is
     * deliberately conservative: a row means this box has committed to that store's heap, and
     * {@code Restart=always} means its JVM comes back whether or not it is up at this instant.
     */
    private void requireCapacity() {
        long totalMb = ops.totalMemoryMb();
        long committedStores = hostedInstanceRepository.count();
        if (!HostedCapacity.hasRoomForAnotherStore(totalMb, committedStores,
                StoreUnitRenderer.HEAP_MB, NON_STORE_RESERVE_MB, RAM_MARGIN_MB)) {
            throw new UnavailableException("Not enough RAM for another hosted store: " + totalMb
                    + " MB total, " + committedStores + " store(s) already committed to "
                    + StoreUnitRenderer.HEAP_MB + " MB of heap each, " + NON_STORE_RESERVE_MB
                    + " MB reserved for the admin backend and the OS, and another store needs "
                    + StoreUnitRenderer.HEAP_MB + " MB plus a " + RAM_MARGIN_MB + " MB margin."
                    + " The fix is a bigger server, not a smaller margin.");
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
            throw new UnavailableException(property + " is not configured; hosted stores cannot be "
                    + "provisioned without it");
        }
    }

    private static void requireSafeIdentifier(String what, String value) {
        if (value == null || value.isBlank()) {
            throw new RefusedException(what + " is required");
        }
        for (char forbidden : FORBIDDEN_IN_IDENTIFIERS) {
            if (value.indexOf(forbidden) >= 0) {
                throw new RefusedException("Unsafe " + what + " '" + value + "': it contains "
                        + describe(forbidden));
            }
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new RefusedException("Unsafe " + what + " '" + value + "': letters, digits, "
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
            throw new RefusedException("expiresAt must be an ISO date (yyyy-MM-dd), got '"
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
