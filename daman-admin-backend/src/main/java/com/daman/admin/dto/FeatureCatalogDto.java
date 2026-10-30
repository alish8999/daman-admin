package com.daman.admin.dto;

import com.daman.admin.catalog.FeatureCatalog;

import java.util.List;

/**
 * Wire shape of the feature catalog, served to shipped POS installs and to the admin portal.
 *
 * <p><b>Prices only, no display text.</b> Titles, descriptions and benefit lines stay in each
 * frontend's own translation service, because they need an English and an Arabic pair and that copy
 * is already written there. Prices are the part that changes; copy is not. Putting copy on the wire
 * would mean this backend owning two languages' marketing text and the POS needing a new fetch to
 * fix a typo.
 */
public record FeatureCatalogDto(String currency, List<Feature> features) {

    public record Feature(String key, Integer price, boolean contactUs, String bundledWith) { }

    /** Built once at startup — see {@code FeatureCatalogController}; there is nothing per-request. */
    public static FeatureCatalogDto of(List<FeatureCatalog.Entry> entries) {
        return new FeatureCatalogDto("USD", entries.stream()
                .map(e -> new Feature(e.key(), e.priceUsd(), e.contactUs(), e.bundledWith()))
                .toList());
    }
}
