package com.daman.admin.service;

import com.daman.admin.entity.License;
import com.daman.admin.event.LicenceRemintedEvent;
import com.daman.admin.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single owner of re-minting a licence key when its effective feature set changes.
 *
 * <p>This is what makes a feature sale reach the shop that bought it. Before this existed,
 * saving the Features tab wrote a JSON column and stopped: the key — which on a generic build is
 * the only thing that actually turns a feature on — kept whatever it was minted with until
 * someone happened to renew it.
 *
 * <p>Every rule below is load-bearing, and each is measured against the production fleet
 * (2026-11-17):
 *
 * <ul>
 *   <li><b>Only live licences.</b> 43 of the 101 ACTIVE rows are already expired. Minting for a
 *       locked shop achieves nothing and would have the portal claim a delivery that cannot
 *       land.</li>
 *   <li><b>Never a REVOKED row.</b> Hosted-store re-provisioning revokes and re-mints routinely
 *       ({@code demo1}, {@code c-14} each have a REVOKED row and a fresh ACTIVE one from the same
 *       minute), so resurrecting one would hand a retired store a working key.</li>
 *   <li><b>An orphan is skipped, not thrown.</b> Six licences have no client row; one must never
 *       be able to fail a whole save.</li>
 *   <li><b>Features only.</b> {@link LicenseKeyService#resignWithFeatures} re-signs the existing
 *       payload, so no branding or profile edit rides along and {@code trial: true} survives.</li>
 *   <li><b>A re-mint is not a renewal.</b> {@code expiresAt}, {@code machineId},
 *       {@code clientName} and {@code renewedAt} are untouched.</li>
 * </ul>
 *
 * <p><b>Two different comparisons, deliberately.</b> {@link #remintForClient} mints when the
 * resolved set differs from the key in ANY key the resolved set has — including one the key never
 * carried, so a flag added to the catalogue after that key was minted is picked up. The
 * owner-facing {@link #reconcileDrift} instead mints exactly the rows
 * {@link LicenceDriftScanner} reports, which compares only commonly-present keys: that keeps the
 * preview about genuine disagreements rather than every old key's missing flags.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenceRemintService {

    private final LicenseRepository licenseRepository;
    private final LicenseKeyService licenseKeyService;
    private final ClientConfigService clientConfigService;
    private final LicenceDriftScanner driftScanner;
    private final ApplicationEventPublisher events;

    /**
     * What happened to one licence. {@code skippedReason} is null exactly when {@code minted} is
     * true; it is a fixed string so the portal can show it without parsing prose.
     */
    public record RemintOutcome(Long licenceId, String clientCode, String machineId,
                                boolean minted, String skippedReason) {

        static RemintOutcome minted(License l) {
            return new RemintOutcome(l.getId(), l.getClientCode(), l.getMachineId(), true, null);
        }

        static RemintOutcome skipped(License l, String reason) {
            return new RemintOutcome(l.getId(), l.getClientCode(), l.getMachineId(), false, reason);
        }
    }

    @Transactional
    public List<RemintOutcome> remintForClient(String clientCode) {
        return remintForClient(clientCode, LocalDate.now(), null);
    }

    /**
     * {@code removableFlags} — the flags this operation is ALLOWED to switch off.
     *
     * <p>Normally the keys a save actually changed. Any other flag the stored key grants but the
     * resolved set denies is left ON and the licence is reported as still drifting, because
     * taking a working feature away from a live shop is the owner's explicit decision
     * ({@link #reconcileDrift}) and never a side effect of saving something else. {@code null}
     * means "no protection" and belongs to the reconcile alone.
     */
    @Transactional
    public List<RemintOutcome> remintForClient(String clientCode, java.util.Set<String> removableFlags) {
        return remintForClient(clientCode, LocalDate.now(), removableFlags);
    }

    // No (String, LocalDate) overload: it is ambiguous with (String, Set) under a matcher, and
    // the only caller that needs an explicit date is a test, which uses the 3-arg form.

    /**
     * Every live licence of this client whose key no longer matches its resolved set.
     *
     * <p>Reads the client's licences as a LIST, never through
     * {@code findByMachineIdAndClientCodeAndStatus} — that Optional finder throws when two ACTIVE
     * rows share a machine, which a re-issue can legitimately produce, and dropping a real
     * re-mint because of that would be absurd.
     */
    @Transactional
    public List<RemintOutcome> remintForClient(String clientCode, LocalDate today,
                                               java.util.Set<String> removableFlags) {
        List<RemintOutcome> outcomes = new ArrayList<>();
        for (License l : licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode)) {
            outcomes.add(remint(l, today, removableFlags));
        }
        return outcomes;
    }

    @Transactional
    public RemintOutcome remintLicence(long licenceId) {
        return remintLicence(licenceId, LocalDate.now(), null);
    }

    @Transactional
    public RemintOutcome remintLicence(long licenceId, java.util.Set<String> removableFlags) {
        return remintLicence(licenceId, LocalDate.now(), removableFlags);
    }

    @Transactional
    public RemintOutcome remintLicence(long licenceId, LocalDate today,
                                       java.util.Set<String> removableFlags) {
        License l = licenseRepository.findById(licenceId).orElse(null);
        if (l == null) {
            return new RemintOutcome(licenceId, null, null, false, "not found");
        }
        return remint(l, today, removableFlags);
    }

    @Transactional
    public List<RemintOutcome> reconcileDrift() {
        return reconcileDrift(LocalDate.now());
    }

    /**
     * The owner-run fleet correction (spec §13.1): mint exactly the rows the drift scanner
     * reports.
     *
     * <p>This is the only path that may REMOVE a flag a shop is currently running — the owner
     * ruled that the client record is authoritative, and three live licences ({@code viora} ×2,
     * {@code c-14}) lose a flag by it. It is therefore never a side effect of an ordinary save:
     * the portal previews the rows and the owner presses it.
     */
    @Transactional
    public List<RemintOutcome> reconcileDrift(LocalDate today) {
        List<RemintOutcome> outcomes = new ArrayList<>();
        for (LicenceDriftScanner.DriftRow row : driftScanner.scanAll(today)) {
            // null = nothing is protected. This is the explicit remover.
            outcomes.add(remintLicence(row.licenceId(), today, null));
        }
        return outcomes;
    }

    private RemintOutcome remint(License licence, LocalDate today,
                                 java.util.Set<String> removableFlags) {
        if (!LicenceLiveness.isLive(licence, today)) {
            String reason = "ACTIVE".equalsIgnoreCase(
                    licence.getStatus() == null ? "" : licence.getStatus().trim())
                    ? "expired" : "revoked";
            return RemintOutcome.skipped(licence, reason);
        }

        Map<String, Boolean> resolved;
        try {
            resolved = clientConfigService.licenseEntitlementsFor(licence).features();
        } catch (IllegalArgumentException e) {
            log.warn("Re-mint skipped orphan licence {} ({}): {}",
                    licence.getId(), licence.getClientCode(), e.getMessage());
            return RemintOutcome.skipped(licence, "no client");
        }

        JsonNode payload = licenseKeyService.verifiedPayload(licence.getLicenseKey());
        if (payload == null) {
            log.warn("Re-mint skipped licence {} ({}): stored key does not verify",
                    licence.getId(), licence.getClientCode());
            return RemintOutcome.skipped(licence, "unverifiable key");
        }

        // A v1 key has no features object, and re-signing one into a key that carries features
        // produces a HALF-OPEN install: the desktop's applyLicenseEntitlements() bails out below
        // v2 so every backend gate stays shut, while effectiveClientConfig() has no version gate
        // and would light the POS UI anyway — a button that answers 403. A v1 row has to go
        // through POST /api/licenses/reissue-v2 first.
        if (licenseKeyService.payloadVersion(licence.getLicenseKey()) < 2) {
            log.warn("Re-mint skipped licence {} ({}): v1 key, needs reissue-v2 first",
                    licence.getId(), licence.getClientCode());
            return RemintOutcome.skipped(licence, "v1 key - re-issue first");
        }

        JsonNode featuresNode = payload.get("features");
        Map<String, Boolean> inKey = (featuresNode != null && featuresNode.isObject())
                ? LicenceDriftScanner.booleansOf(featuresNode)
                : Map.of();

        Map<String, Boolean> toWrite = featuresToWrite(inKey, resolved, removableFlags);
        if (toWrite.equals(inKey)) {
            // Nothing this operation is allowed to change. Distinguish "already correct" from
            // "the only difference is a removal we are protecting", because the second needs the
            // owner's reconcile and the first needs nothing.
            boolean protectedRemoval = !featuresToWrite(inKey, resolved, null).equals(inKey);
            return RemintOutcome.skipped(licence, protectedRemoval ? "drifting - reconcile first" : "unchanged");
        }

        String oldKey = licence.getLicenseKey();
        String newKey = licenseKeyService.resignWithFeatures(oldKey, toWrite);
        if (newKey == null) {
            log.error("Re-mint failed for licence {} ({}): could not re-sign",
                    licence.getId(), licence.getClientCode());
            return RemintOutcome.skipped(licence, "resign failed");
        }

        // Do NOT clobber a v1 previousLicenseKey. LicenseReissueService stores one only on a
        // genuine v1 -> v2 transition, precisely so a later revert-reissue restores a v1 key
        // rather than another v2 one ("reversible from the DB alone"). A feature re-mint
        // overwriting it would quietly break that guarantee.
        String storedPrevious = licence.getPreviousLicenseKey();
        boolean previousIsV1Rollback = storedPrevious != null && !storedPrevious.isBlank()
                && licenseKeyService.payloadVersion(storedPrevious) < 2;
        if (!previousIsV1Rollback) {
            licence.setPreviousLicenseKey(oldKey);
        }
        licence.setLicenseKey(newKey);
        // expiresAt / machineId / clientName / renewedAt deliberately untouched.
        licenseRepository.save(licence);
        events.publishEvent(new LicenceRemintedEvent(
                licence.getId(), licence.getClientCode(), licence.getMachineId(), newKey));
        log.info("Re-minted licence {} for {} (machine {})",
                licence.getId(), licence.getClientCode(), licence.getMachineId());
        return RemintOutcome.minted(licence);
    }

    /**
     * The feature map to write into the new key: the stored key's own flags, with the resolved
     * set applied EXCEPT where that would switch a flag off which this operation is not allowed
     * to remove.
     *
     * <p>Starts from {@code inKey} rather than {@code resolved} so nothing the key already
     * carries is silently dropped — a retired flag no longer in the catalogue stays as it was.
     *
     * <p>A flag the resolved set has and the key lacks entirely is always adopted: that is a flag
     * added to the catalogue after this key was minted, and withholding it would mean a key could
     * never catch up. Only a true → false transition is gated, because only that one takes
     * something away from a shop.
     *
     * <p>{@code removableFlags} null means every removal is allowed — the owner-run reconcile.
     */
    static Map<String, Boolean> featuresToWrite(Map<String, Boolean> inKey,
                                                Map<String, Boolean> resolved,
                                                java.util.Set<String> removableFlags) {
        Map<String, Boolean> next = new java.util.LinkedHashMap<>(inKey);
        resolved.forEach((key, resolvedValue) -> {
            Boolean keyValue = inKey.get(key);
            if (resolvedValue.equals(keyValue)) return;
            boolean takesSomethingAway = Boolean.TRUE.equals(keyValue) && !resolvedValue;
            if (takesSomethingAway && removableFlags != null && !removableFlags.contains(key)) {
                return;   // leave it ON; the licence stays drifting until the owner reconciles
            }
            next.put(key, resolvedValue);
        });
        return next;
    }
}
