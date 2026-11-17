package com.daman.admin.service;

import com.daman.admin.catalog.FeatureCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which licence feature flags a trial unlocks, from the shop type the visitor picked.
 *
 * <p>The result is a FULL map (every flag explicitly true/false), mirroring what
 * {@code ClientConfigService.licenseEntitlementsFor()} builds, so a trial forces unlisted flags
 * off rather than inheriting the desktop's config default. {@code quickPickCards} is deliberately
 * absent: it stopped being a licence flag on 2026-10-19 (the desktop reads
 * {@code app_settings['ui.quickSale']}).
 *
 * <p>An optional {@code trial.presets} override ({@code {"cafe":{"accounting":true}}}, per-type
 * flag deltas layered on the base) may be set by an admin. Anything malformed or empty falls back
 * to the built-in presets, never to "no features".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialPresetService {

    /**
     * Every feature that is for sale: a trial never turns one on, whatever the shop type is
     * and whatever an override says.
     *
     * <p>Owner, 2026-11-17 - the kitchen printer, device repair, accounting, recipes and
     * manufacturing were arriving switched on in a trial. A trial exists to show the product
     * and let a shop find out which add-ons it wants; handing them over unasked both gives away
     * the thing being sold and makes the first invoice after the trial read as a takeaway.
     *
     * <p>Read from {@link FeatureCatalog} rather than listed here, so an add-on priced there
     * leaves trials the same day it is priced. A {@code 0} price is free and included (cafeMode,
     * invoiceSettings, autoBackup, fixedAssets, simulatePosMode) and is NOT excluded; a flag the
     * catalog does not mention at all is an unpriced visibility switch (reports, scaleBarcodes,
     * itemLedger ...) and is not excluded either. {@code newBranch} is in the catalog but is not
     * a feature flag, so it never matches a key here.
     */
    private static final Set<String> PAID_ADD_ONS = FeatureCatalog.ENTRIES.stream()
            .filter(e -> e.priceUsd() == null || e.priceUsd() > 0)
            .map(FeatureCatalog.Entry::key)
            .collect(Collectors.toUnmodifiableSet());

    /** Flags excluded from every map, whatever an override says. */
    private static final String QUICK_PICK_CARDS = "quickPickCards";

    /**
     * Default-enabled flags in client-form.component.ts's {@code features} FormGroup
     * (those defaulting to {@code [true]}); all others are false. 33 flags in total.
     */
    private static final List<String> ENABLED_BY_DEFAULT = List.of(
            "multiLanguage", "barcode", "reports", "suppliers", "multiCurrency",
            "clientLedger", "supplierLedger", "fractionalQuantity", "accountStatement",
            "itemLedger", "batchStocktake", "bulkPriceUpdate", "invoiceSettings",
            "autoBackup", "fixedAssets");

    private static final List<String> DISABLED_BY_DEFAULT = List.of(
            "seedDemoData", "shifts", "multiCurrencyPricing", "productRecipes", "manufacturing",
            "userManagement", "quotation", "accounting", "currencyExchange", "kitchenPrinter",
            "scaleBarcodes", "posTerminals", "mobilePos", "simulatePosMode", "tableOrders", "cafeMode",
            "consignment", "shareholders", "deviceRepair", "googleDriveBackup");

    public static final Map<String, Boolean> BASE_FEATURES = buildBase();

    /** Flags each shop type adds to the base. {@code tableOrders} is gated on hasTables separately. */
    private static final Map<String, List<String>> BUILT_IN = Map.of(
            "general", List.of(),
            "supermarket", List.of("scaleBarcodes"),
            "cafe", List.of("tableOrders", "cafeMode", "productRecipes"),
            "restaurant", List.of("tableOrders", "productRecipes", "kitchenPrinter"),
            "electronics", List.of("deviceRepair"),
            "wholesale", List.of("consignment"));

    /**
     * Every key here must exist in the admin frontend's {@code storeTypeValues} (the frontend list is a
     * superset: 15 values vs 6 here), not the reverse. NOTE: {@code List.copyOf(Map.of(..).keySet())} has
     * JVM-dependent order; harmless today as only {@code .contains()} is used, but if a future picker
     * endpoint serves this list, sort it first.
     */
    public static final List<String> KNOWN_STORE_TYPES = List.copyOf(BUILT_IN.keySet());

    private final TrialSettingsService settings;
    private final ObjectMapper objectMapper;

    public Map<String, Boolean> featuresFor(String storeType, boolean hasTables) {
        Map<String, Boolean> out = new LinkedHashMap<>(BASE_FEATURES);
        Map<String, Boolean> override = overrideFor(storeType);
        if (override != null) {
            out.putAll(override);
        } else {
            for (String flag : BUILT_IN.getOrDefault(storeType == null ? "" : storeType, List.of())) {
                out.put(flag, true);
            }
        }
        if (!hasTables) {
            out.put("tableOrders", false);
        }
        out.remove(QUICK_PICK_CARDS);
        // Last, so it covers the built-in presets AND an override: neither can sell.
        PAID_ADD_ONS.forEach(flag -> out.computeIfPresent(flag, (k, v) -> false));
        return Collections.unmodifiableMap(out);
    }

    /** The override's flag deltas for this type, or null when there is no usable override for it. */
    private Map<String, Boolean> overrideFor(String storeType) {
        String json = settings.presetsJson();
        if (json == null || storeType == null) {
            return null;
        }
        try {
            Map<String, Map<String, Boolean>> parsed = objectMapper.readValue(
                    json, new TypeReference<Map<String, Map<String, Boolean>>>() {});
            if (parsed == null || parsed.isEmpty()) {
                log.warn("trial.presets is empty; using built-in presets");
                return null;
            }
            Map<String, Boolean> forType = parsed.get(storeType);
            if (forType == null) {
                return null;
            }
            Map<String, Boolean> clean = new LinkedHashMap<>();
            forType.forEach((k, v) -> {
                if (k != null && v != null && !QUICK_PICK_CARDS.equals(k)) {
                    clean.put(k, v);
                }
            });
            return clean;
        } catch (RuntimeException e) {
            log.warn("trial.presets is not valid JSON of the expected shape; using built-in presets: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Boolean> buildBase() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        ENABLED_BY_DEFAULT.forEach(f -> m.put(f, true));
        DISABLED_BY_DEFAULT.forEach(f -> m.put(f, false));
        return Collections.unmodifiableMap(m);
    }
}
