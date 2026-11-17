package com.daman.admin.controller;

import com.daman.admin.entity.License;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.FeatureOverride;
import com.daman.admin.service.LicenceDriftScanner;
import com.daman.admin.service.LicenceRemintService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-licence feature overrides, and the drift preview / reconcile pair.
 *
 * <p><b>Session-guarded, like every other {@code /api/licenses/**} route.</b> None of these
 * belongs in {@code AuthFilter.PUBLIC_PATHS}: they write licence keys. {@code AuthFilterPublicPathsTest}
 * pins that by behaviour.
 *
 * <p>Separate from {@link LicenseController} on purpose — that class already owns minting,
 * renewing, revoking and the v2 re-issue batch, and this is a different job: how one licence
 * differs from its client, and how the fleet's keys are brought back in step.
 */
@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenceFeatureController {

    private final LicenseRepository licenseRepository;
    private final ClientConfigService clientConfigService;
    private final LicenceDriftScanner driftScanner;
    private final LicenceRemintService remintService;
    private final ObjectMapper objectMapper;

    /**
     * What the licence now grants, what it overrides, and whether its key was re-minted.
     *
     * <p>The effective set travels back so the licence drawer never has to recompute what the
     * server just decided — and so it cannot disagree with it.
     */
    /** {@code licenseKey} is the licence's CURRENT key: the re-mint above replaced it, and a
     *  caller that kept the one it opened with would hand out a superseded key on download. */
    public record FeatureUpdateResponse(Map<String, Boolean> effectiveFeatures,
                                        Map<String, Boolean> featureOverride,
                                        boolean reminted,
                                        String skippedReason,
                                        String licenseKey) {}

    /**
     * Change how this one licence differs from its client's default feature set.
     *
     * <p><b>A merge patch, not a replacement.</b> A key with {@code true}/{@code false} starts or
     * changes an override; a key with {@code null} stops overriding it and lets the business
     * default through again; and an <b>empty body clears the override entirely</b>, putting the
     * column back to null. That last state is what "inherit everything" means, so it has to be
     * reachable from the UI rather than only from SQL.
     *
     * <p>An unknown flag name is a 400, never a silent write: a stale UI or a typo would
     * otherwise leave a permanently inert override that reads as deliberate.
     *
     * <p>Saves, then re-mints this licence's key so the change reaches the shop (within 12 hours
     * of it being online, or immediately for a hosted store).
     */
    @PutMapping("/{id}/features")
    public ResponseEntity<?> setFeatures(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, Boolean> body) {
        License licence = licenseRepository.findById(id).orElse(null);
        if (licence == null) return ResponseEntity.notFound().build();

        Map<String, Boolean> patch = body == null ? Map.of() : body;
        Set<String> known = clientConfigService.knownFeatureKeys();
        Set<String> unknown = new TreeSet<>(patch.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown feature keys: " + String.join(", ", unknown)));
        }

        Map<String, Boolean> next;
        if (patch.isEmpty()) {
            // Explicitly "stop overriding anything" rather than "change nothing".
            next = Map.of();
        } else {
            next = new LinkedHashMap<>(FeatureOverride.parse(licence.getFeaturesOverrideJson(), objectMapper));
            patch.forEach((key, value) -> {
                if (value == null) next.remove(key);
                else next.put(key, value);
            });
        }

        licence.setFeaturesOverrideJson(FeatureOverride.write(next, objectMapper));
        licenseRepository.save(licence);

        // Only the flags this patch named may be switched off in the key. Any OTHER flag the key
        // grants but the resolved set denies is a pre-existing drift, and taking it back belongs
        // to the owner's reconcile — not to a save that touched a different flag.
        LicenceRemintService.RemintOutcome outcome = remintService.remintLicence(id, patch.keySet());

        Map<String, Boolean> effective;
        try {
            effective = clientConfigService.licenseEntitlementsFor(licence).features();
        } catch (IllegalArgumentException e) {
            // An orphan licence (no client row — six exist in production). The override is saved;
            // there is simply no client to resolve an effective set against.
            effective = Map.of();
        }
        return ResponseEntity.ok(new FeatureUpdateResponse(
                effective,
                FeatureOverride.parse(licence.getFeaturesOverrideJson(), objectMapper),
                outcome.minted(),
                outcome.skippedReason(),
                licence.getLicenseKey()));
    }

    /**
     * Every live licence whose stored key disagrees with its resolved entitlements. Reads only —
     * mints nothing.
     */
    @GetMapping("/features-drift")
    public List<LicenceDriftScanner.DriftRow> featuresDrift() {
        return driftScanner.scanAll();
    }

    /**
     * Correct the drift: re-mint exactly the rows the preview lists.
     *
     * <p><b>The only path that may take a flag away from a shop that is currently running it.</b>
     * The owner ruled the client record authoritative, and three live licences lose a flag by it,
     * so this is a deliberate action behind a preview — never a side effect of saving something
     * else.
     */
    @PostMapping("/features-drift/reconcile")
    public List<LicenceRemintService.RemintOutcome> reconcileFeaturesDrift() {
        return remintService.reconcileDrift();
    }
}
