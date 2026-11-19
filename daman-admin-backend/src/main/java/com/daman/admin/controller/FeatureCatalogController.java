package com.daman.admin.controller;

import com.daman.admin.dto.FeatureCatalogDto;
import com.daman.admin.entity.FeaturePrice;
import com.daman.admin.service.FeaturePriceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves the add-on price list twice, at two paths, from one table.
 *
 * <p>{@code /api/public/feature-catalog} is ANONYMOUS (listed in {@code AuthFilter.PUBLIC_PATHS}).
 * Shipped POS installs hold no admin credential, so their backend fetches it unauthenticated. See
 * the justification on the {@code PUBLIC_PATHS} entry itself.
 *
 * <p>{@code /api/feature-catalog} is the same body behind the admin session, for the admin portal.
 * The portal could have used the public path, but keeping the anonymous surface to exactly one
 * endpoint per purpose is worth more than saving a method: the public one can then never acquire an
 * admin-only field by accident.
 *
 * <p><b>The response is still built once.</b> It used to be a {@code static final} computed from a
 * compile-time constant; now that prices are editable it is a cached snapshot, rebuilt only when a
 * price actually changes. That keeps the property the constant gave us and which still matters:
 * there is no rate limiter in front of this backend, so a flood against the anonymous path must
 * cost one serialisation and no database work at all.
 *
 * <p><b>Writing is admin-only.</b> {@code PUT /api/feature-catalog/{key}} is deliberately not under
 * {@code /api/public/}, so it is gated by the same filter as everything else in the portal.
 */
@RestController
@RequiredArgsConstructor
public class FeatureCatalogController {

    private final FeaturePriceService prices;

    /** Null until the first read; cleared by every write. */
    private final AtomicReference<FeatureCatalogDto> cached = new AtomicReference<>();

    @Data
    public static class PriceRequest {
        private Integer price;
        private Boolean contactUs;
        private String bundledWith;
    }

    @GetMapping("/api/public/feature-catalog")
    public ResponseEntity<FeatureCatalogDto> publicCatalog() {
        return ResponseEntity.ok(body());
    }

    @GetMapping("/api/feature-catalog")
    public ResponseEntity<FeatureCatalogDto> catalog() {
        return ResponseEntity.ok(body());
    }

    /**
     * Sets one add-on's price. The next fetch by any shop — within its six-hour catalog TTL —
     * carries the new figure; no new installer and no redeploy.
     */
    @PutMapping("/api/feature-catalog/{key}")
    public ResponseEntity<?> setPrice(@PathVariable String key, @RequestBody PriceRequest body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "a body is required"));
        try {
            FeaturePrice saved = prices.update(key, body.getPrice(),
                    Boolean.TRUE.equals(body.getContactUs()), body.getBundledWith());
            cached.set(null);
            return ResponseEntity.ok(new FeatureCatalogDto.Feature(
                    saved.getKey(), saved.getPriceUsd(), saved.isContactUs(), saved.getBundledWith()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private FeatureCatalogDto body() {
        FeatureCatalogDto snapshot = cached.get();
        if (snapshot != null) return snapshot;
        List<FeaturePrice> rows = prices.all();
        FeatureCatalogDto built = FeatureCatalogDto.ofRows(rows);
        // Racing readers may each build one; they are equal, and a lock on the hot anonymous
        // path would cost more than the duplicate work it saves.
        cached.compareAndSet(null, built);
        return built;
    }
}
