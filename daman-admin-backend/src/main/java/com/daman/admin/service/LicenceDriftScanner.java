package com.daman.admin.service;

import com.daman.admin.entity.License;
import com.daman.admin.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares what each stored licence key actually grants against what it should grant.
 *
 * <p><b>On a generic build — which every client now is — the signed key is the only thing that
 * turns a feature on.</b> The bundled {@code client.config.generic.json} is all-{@code false},
 * so {@code features_json} is the admin's intent and the key is what the shop is running. The two
 * can disagree, and on 2026-11-17 eight of ninety-five comparable live licences did:
 *
 * <ul>
 *   <li><b>false → true</b> (five rows): the owner enabled a feature and no key was ever
 *       re-minted. This is the bug the re-mint path fixes.</li>
 *   <li><b>true → false</b> (three rows, two of them real shops): the running key grants
 *       something the client record denies — {@code viora}'s {@code multiCurrencyPricing},
 *       {@code c-14}'s {@code consignment}. Correcting these takes a working feature away from a
 *       live shop, which is why the reconcile is previewed and owner-run rather than a side
 *       effect of an unrelated save.</li>
 * </ul>
 *
 * <p>Only keys present in BOTH sides are compared. A flag added to the catalogue after a key was
 * minted is not drift, and neither is a retired flag still sitting in an old payload — reporting
 * either would bury the real rows in noise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenceDriftScanner {

    private final LicenseRepository licenseRepository;
    private final LicenseKeyService licenseKeyService;
    private final ClientConfigService clientConfigService;

    /** One flag that differs: what the stored key says, and what it should say. */
    public record FlagChange(String key, boolean inKey, boolean resolved) {}

    /** One licence whose key disagrees with its resolved entitlements. */
    public record DriftRow(Long licenceId, String clientCode, String label, String machineId,
                           List<FlagChange> changes) {}

    public List<DriftRow> scanAll() {
        return scanAll(LocalDate.now());
    }

    public List<DriftRow> scanAll(LocalDate today) {
        return scan(licenseRepository.findAllByOrderByActivatedAtDesc(), today);
    }

    public List<DriftRow> scanClient(String clientCode) {
        return scanClient(clientCode, LocalDate.now());
    }

    public List<DriftRow> scanClient(String clientCode, LocalDate today) {
        return scan(licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode), today);
    }

    private List<DriftRow> scan(List<License> licences, LocalDate today) {
        List<DriftRow> rows = new ArrayList<>();
        for (License l : licences) {
            driftOf(l, today).ifPresent(rows::add);
        }
        return rows;
    }

    /**
     * This licence's drift, or empty when there is none — and also empty when the licence cannot
     * be compared at all: not live, no client row, or a key that does not verify.
     *
     * <p>Skipping rather than throwing is the point. Six orphan licences exist in production, and
     * one of them must never be able to abort a fleet scan.
     */
    public java.util.Optional<DriftRow> driftOf(License licence, LocalDate today) {
        if (!LicenceLiveness.isLive(licence, today)) return java.util.Optional.empty();

        JsonNode payload = licenseKeyService.verifiedPayload(licence.getLicenseKey());
        if (payload == null) {
            log.debug("Drift scan skipped licence {}: key does not verify", licence.getId());
            return java.util.Optional.empty();
        }
        JsonNode featuresNode = payload.get("features");
        if (featuresNode == null || !featuresNode.isObject()) return java.util.Optional.empty();

        Map<String, Boolean> resolved;
        try {
            resolved = clientConfigService.licenseEntitlementsFor(licence).features();
        } catch (IllegalArgumentException e) {
            log.debug("Drift scan skipped orphan licence {} ({})", licence.getId(), licence.getClientCode());
            return java.util.Optional.empty();
        }

        List<FlagChange> changes = changes(booleansOf(featuresNode), resolved);
        if (changes.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new DriftRow(licence.getId(), licence.getClientCode(),
                licence.getLabel(), licence.getMachineId(), changes));
    }

    /**
     * The comparison rule, pure and static so the re-mint path shares it rather than
     * reimplementing it: the flags present in both maps whose values differ.
     */
    public static List<FlagChange> changes(Map<String, Boolean> inKey, Map<String, Boolean> resolved) {
        List<FlagChange> changes = new ArrayList<>();
        if (inKey == null || resolved == null) return changes;
        inKey.forEach((key, keyValue) -> {
            Boolean resolvedValue = resolved.get(key);
            if (resolvedValue != null && !resolvedValue.equals(keyValue)) {
                changes.add(new FlagChange(key, keyValue, resolvedValue));
            }
        });
        return changes;
    }

    /** A payload's {@code features} object as a plain map; non-boolean entries are ignored. */
    static Map<String, Boolean> booleansOf(JsonNode featuresNode) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        featuresNode.properties().forEach(e -> {
            if (e.getValue() != null && e.getValue().isBoolean()) {
                out.put(e.getKey(), e.getValue().asBoolean());
            }
        });
        return out;
    }
}
