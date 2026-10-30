package com.daman.admin.controller;

import com.daman.admin.catalog.FeatureCatalog;
import com.daman.admin.dto.FeatureCatalogDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the add-on price list twice, at two paths, from one constant.
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
 * <p><b>The response is built once.</b> {@link #BODY} is computed at class-init from a compile-time
 * constant, so a flood against the anonymous path costs one serialisation per request and no
 * computation at all. There is no rate limiter in front of this backend, so "cheap per call" is the
 * whole defence.
 */
@RestController
public class FeatureCatalogController {

    private static final FeatureCatalogDto BODY = FeatureCatalogDto.of(FeatureCatalog.ENTRIES);

    @GetMapping("/api/public/feature-catalog")
    public ResponseEntity<FeatureCatalogDto> publicCatalog() {
        return ResponseEntity.ok(BODY);
    }

    @GetMapping("/api/feature-catalog")
    public ResponseEntity<FeatureCatalogDto> catalog() {
        return ResponseEntity.ok(BODY);
    }
}
