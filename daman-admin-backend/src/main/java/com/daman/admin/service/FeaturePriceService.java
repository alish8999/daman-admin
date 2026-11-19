package com.daman.admin.service;

import com.daman.admin.catalog.FeatureCatalog;
import com.daman.admin.entity.FeaturePrice;
import com.daman.admin.repository.FeaturePriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The add-on prices the whole product quotes from.
 *
 * <p>{@code FeatureCatalog.ENTRIES} remains the list of what is SELLABLE and the figures a new
 * installation starts with; this table is what those figures currently are. Nothing may be
 * priced that the catalogue does not name, because a key the catalogue does not name is not a
 * product — it would reach a shop's features page as a card nothing can be sold against.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeaturePriceService {

    private final FeaturePriceRepository repository;

    /** Catalogue order, which is the order the portal and the POS both show. */
    private static final Map<String, Integer> ORDER = buildOrder();
    private static final Set<String> SELLABLE = FeatureCatalog.ENTRIES.stream()
            .map(FeatureCatalog.Entry::key).collect(Collectors.toUnmodifiableSet());

    private static Map<String, Integer> buildOrder() {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < FeatureCatalog.ENTRIES.size(); i++) order.put(FeatureCatalog.ENTRIES.get(i).key(), i);
        return Map.copyOf(order);
    }

    /**
     * Gives every sellable add-on a row, once.
     *
     * <p>Runs on every boot and is a no-op after the first, which is what makes adding a new
     * add-on to the catalogue enough on its own: it appears here at the price the catalogue
     * proposes, and the owner changes it if they disagree.
     *
     * <p><b>It never touches a row that exists.</b> Re-seeding would quietly reset a price to
     * whatever the Java file happens to say on the day of a deploy — undoing the owner's own
     * edit, which is the single thing this table exists to prevent.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedMissingRows() {
        Set<String> have = repository.findAll().stream().map(FeaturePrice::getKey).collect(Collectors.toSet());
        List<FeaturePrice> missing = new ArrayList<>();
        for (FeatureCatalog.Entry entry : FeatureCatalog.ENTRIES) {
            if (have.contains(entry.key())) continue;
            FeaturePrice row = new FeaturePrice();
            row.setKey(entry.key());
            row.setPriceUsd(entry.priceUsd());
            row.setContactUs(entry.contactUs());
            row.setBundledWith(entry.bundledWith());
            row.setSortOrder(ORDER.getOrDefault(entry.key(), Integer.MAX_VALUE));
            missing.add(row);
        }
        if (missing.isEmpty()) return;
        repository.saveAll(missing);
        log.info("Seeded {} feature price row(s) from the catalogue: {}",
                missing.size(), missing.stream().map(FeaturePrice::getKey).toList());
    }

    /**
     * Every priced add-on, in catalogue order.
     *
     * <p>Rows for keys the catalogue no longer sells are filtered out rather than deleted: a
     * retired add-on's price is worth keeping if it ever comes back, and a shop must not be
     * shown a card for something that is no longer a product.
     */
    public List<FeaturePrice> all() {
        return repository.findAllByOrderBySortOrderAsc().stream()
                .filter(p -> SELLABLE.contains(p.getKey()))
                .toList();
    }

    /**
     * Sets one add-on's price.
     *
     * @throws IllegalArgumentException when the result would be something nothing can quote —
     *         an unknown key, a priced row with no figure, a negative figure, or a prerequisite
     *         that is not itself an add-on
     */
    @Transactional
    public FeaturePrice update(String key, Integer priceUsd, boolean contactUs, String bundledWith) {
        if (key == null || !SELLABLE.contains(key)) {
            throw new IllegalArgumentException("unknown feature key: " + key);
        }
        String prerequisite = bundledWith == null || bundledWith.isBlank() ? null : bundledWith.trim();
        if (prerequisite != null) {
            if (prerequisite.equals(key)) {
                throw new IllegalArgumentException("an add-on cannot be bundled with itself");
            }
            if (!SELLABLE.contains(prerequisite)) {
                throw new IllegalArgumentException("unknown prerequisite: " + prerequisite);
            }
        }
        // "Contact us" and a figure are mutually exclusive, and the figure is CLEARED rather
        // than left in the row: something downstream would eventually render it.
        Integer price = contactUs ? null : priceUsd;
        if (!contactUs) {
            if (price == null) throw new IllegalArgumentException("a price is required unless the add-on is priced on enquiry");
            if (price < 0) throw new IllegalArgumentException("a price cannot be negative");
        }

        FeaturePrice row = repository.findById(key).orElseGet(() -> {
            FeaturePrice fresh = new FeaturePrice();
            fresh.setKey(key);
            fresh.setSortOrder(ORDER.getOrDefault(key, Integer.MAX_VALUE));
            return fresh;
        });
        row.setPriceUsd(price);
        row.setContactUs(contactUs);
        row.setBundledWith(prerequisite);
        return repository.save(row);
    }
}
