package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * What one add-on costs, as the owner has set it.
 *
 * <p><b>Why this is a table now.</b> Until 2026-11-19 the prices were a constant list in
 * {@code FeatureCatalog.java}: changing one meant editing Java and redeploying this backend,
 * which the owner accepted at the time in place of a screen. It stopped being acceptable when
 * the same figures turned out to be written out by hand in two more places that had already
 * drifted — the shipped POS fallback (which was missing {@code mobilePos} entirely) and the
 * public website, whose tutorial pages were quoting $39 for Product Recipes while the app
 * quoted $25. One editable row per add-on, and everything else generated from it.
 *
 * <p><b>{@code FeatureCatalog.ENTRIES} is still here, and is still load-bearing.</b> It is the
 * SEED — the figures this table is created with on first boot — and the list of what is
 * sellable at all. A key the catalogue does not know cannot be priced, because a key the
 * catalogue does not know is not a product.
 *
 * <p><b>Which database.</b> The shops read this through {@code admin-api.damansoft.com}, so the
 * LIVE admin database is the authoritative copy and the live portal is where a price is
 * changed. A price edited on a developer's localhost portal reaches nobody.
 */
@Entity
@Table(name = "feature_prices")
@Getter
@Setter
public class FeaturePrice {

    /**
     * The catalogue key, and the primary key — there is exactly one price per add-on and the key
     * is already unique and stable, so a surrogate id would only add a second way to say the
     * same thing.
     */
    @Id
    @Column(name = "feature_key", length = 64, nullable = false)
    private String key;

    /**
     * USD, whole dollars. Null exactly when {@link #contactUs} is true.
     *
     * <p>{@code 0} means free and included, which is NOT the same as absent: the features page
     * says "Free" from this row rather than from a hardcoded list of which add-ons are free.
     */
    @Column(name = "price_usd")
    private Integer priceUsd;

    /** Priced on enquiry — the POS modal shows "contact us" rather than a figure. */
    @Column(nullable = false)
    private boolean contactUs;

    /**
     * The key of a prerequisite whose price this one is the increment ON TOP OF, or null when
     * the price stands alone. {@code manufacturing} is marginal on {@code productRecipes}, so a
     * shop holding both pays the bundle rather than the two prices twice over.
     */
    @Column(name = "bundled_with", length = 64)
    private String bundledWith;

    /** Display order in the portal, taken from the seed list's own order. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void stampUpdatedAt() {
        updatedAt = LocalDateTime.now();
    }
}
