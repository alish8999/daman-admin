package com.daman.admin.service;

import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage-1 of the license-driven distribution migration (see
 * Docs/superpowers/specs/2026-09-04-license-driven-distribution-design.md §6.2 / §7):
 * re-issue every existing client a v2 licence for their SAME machine + clientCode,
 * with baseCurrency + features filled from that client's stored ClientConfig.
 *
 * <p>In-place and idempotent: it overwrites {@code License.licenseKey} and bumps
 * {@code renewedAt} on the existing row — never inserts a new one. Re-running is a
 * safe no-op-shaped operation (regenerates an equivalent key).
 */
@Service
@RequiredArgsConstructor
public class LicenseReissueService {

    private static final String ACTIVE = "ACTIVE";

    private final LicenseRepository licenseRepository;
    private final ClientConfigRepository clientConfigRepository;
    private final ClientConfigService clientConfigService;
    private final LicenseKeyService licenseKeyService;

    public record ReissuePreviewRow(String clientCode, String clientName, String machineId,
                                    int currentVersion, String expiresAt, boolean clientConfigPresent) {}

    public record ReissuePreview(List<ReissuePreviewRow> eligible, List<ReissuePreviewRow> skipped) {}

    public record ReissuedLicence(String clientCode, String clientName, String machineId,
                                  int fromVersion, String datFileName,
                                  String newKey, String previousKey) {}

    public record ReissueResult(List<ReissuedLicence> reissued, List<ReissuePreviewRow> skipped) {}

    /** Read-only. Splits ACTIVE licences into eligible (ClientConfig present) vs skipped (orphaned). */
    public ReissuePreview preview() {
        List<ReissuePreviewRow> eligible = new ArrayList<>();
        List<ReissuePreviewRow> skipped = new ArrayList<>();
        for (License l : licenseRepository.findAllByOrderByActivatedAtDesc()) {
            if (!ACTIVE.equals(l.getStatus())) continue;
            boolean present = clientConfigRepository.findByClientCode(l.getClientCode()).isPresent();
            ReissuePreviewRow row = rowOf(l, present);
            (present ? eligible : skipped).add(row);
        }
        return new ReissuePreview(eligible, skipped);
    }

    /**
     * Regenerate a v2 key in place for every ACTIVE licence whose ClientConfig exists.
     * Orphaned ACTIVE licences are returned in {@code skipped}, not an error.
     */
    @Transactional
    public ReissueResult reissueAll() {
        List<License> actives = licenseRepository.findAllByOrderByActivatedAtDesc().stream()
                .filter(l -> ACTIVE.equals(l.getStatus()))
                .toList();

        Map<String, Integer> perClient = new HashMap<>();
        for (License l : actives) perClient.merge(l.getClientCode(), 1, Integer::sum);

        List<ReissuedLicence> reissued = new ArrayList<>();
        List<ReissuePreviewRow> skipped = new ArrayList<>();

        for (License l : actives) {
            var cfgOpt = clientConfigRepository.findByClientCode(l.getClientCode());
            if (cfgOpt.isEmpty()) {
                skipped.add(rowOf(l, false));
                continue;
            }
            int fromVersion = licenseKeyService.payloadVersion(l.getLicenseKey());
            String previousKey = l.getLicenseKey();
            String clientName = l.getClientName() != null ? l.getClientName() : cfgOpt.get().getAppName();
            String expiresAt = l.getExpiresAt() != null ? l.getExpiresAt().toString() : null;

            var ent = clientConfigService.licenseEntitlementsFor(l.getClientCode());
            String newKey = licenseKeyService.generateLicense(
                    l.getMachineId(), clientName, l.getClientCode(), expiresAt,
                    ent.baseCurrency(), ent.features(),
                    ent.colorPrimary(), ent.colorSecondary());

            // Only capture a genuine v1 -> v2 transition. On a second batch run the row
            // is already v2, so leave the stored original v1 key untouched — otherwise a
            // later revertReissue would restore a v2 key, breaking "reversible from the DB alone".
            if (fromVersion < 2) {
                l.setPreviousLicenseKey(previousKey);
            }
            l.setLicenseKey(newKey);
            l.setClientName(clientName);
            l.setRenewedAt(LocalDateTime.now());
            licenseRepository.save(l);

            String datName = perClient.getOrDefault(l.getClientCode(), 1) > 1
                    ? l.getClientCode() + "__" + shortMachine(l.getMachineId()) + ".dat"
                    : l.getClientCode() + ".dat";

            reissued.add(new ReissuedLicence(l.getClientCode(), clientName, l.getMachineId(),
                    fromVersion, datName, newKey, previousKey));
        }
        return new ReissueResult(reissued, skipped);
    }

    public record RevertResult(String clientCode, String machineId, String restoredKey) {}

    /**
     * Undo the last in-place v2 re-issue for one licence: put {@code previousLicenseKey}
     * back as the active key and clear it (so a second call is a no-op-shaped error).
     * Throws {@link IllegalArgumentException} for an unknown id or a row that was
     * never re-issued.
     */
    @Transactional
    public RevertResult revertReissue(long licenseId) {
        License l = licenseRepository.findById(licenseId)
                .orElseThrow(() -> new IllegalArgumentException("No licence with id " + licenseId));
        String prev = l.getPreviousLicenseKey();
        if (prev == null || prev.isBlank()) {
            throw new IllegalArgumentException("Licence " + licenseId + " has no previous key to revert to");
        }
        l.setLicenseKey(prev);
        l.setPreviousLicenseKey(null);
        licenseRepository.save(l);
        return new RevertResult(l.getClientCode(), l.getMachineId(), prev);
    }

    private ReissuePreviewRow rowOf(License l, boolean clientConfigPresent) {
        return new ReissuePreviewRow(
                l.getClientCode(), l.getClientName(), l.getMachineId(),
                licenseKeyService.payloadVersion(l.getLicenseKey()),
                l.getExpiresAt() != null ? l.getExpiresAt().toString() : null,
                clientConfigPresent);
    }

    /** First 9 alphanumerics of a machineId, for disambiguating a multi-machine client's .dat files. */
    private static String shortMachine(String machineId) {
        if (machineId == null) return "unknown";
        String s = machineId.replaceAll("[^A-Za-z0-9]", "");
        return s.length() <= 9 ? s : s.substring(0, 9);
    }
}
