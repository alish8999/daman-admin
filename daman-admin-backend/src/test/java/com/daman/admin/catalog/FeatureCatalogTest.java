package com.daman.admin.catalog;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the single source of truth for add-on prices.
 *
 * <p>This constant replaces three disagreeing price lists: the POS "PRO" modal's markup, the
 * per-card comments on the POS features page, and {@code feature-catalog.ts}'s own {@code price}
 * field. The figures below are {@code feature-catalog.ts}'s, verbatim, because it was the only one
 * of the three that was complete and internally consistent — see the design doc's §9.
 *
 * <p>The fifteen {@code price: null} toggles deliberately have NO entry here. They are per-client
 * visibility switches, never sold and never advertised, so a row for one would be dead data that a
 * future reader could mistake for a product.
 */
class FeatureCatalogTest {

    /** Every key→price pair, copied from the design doc's §9 table. */
    private static final Map<String, Integer> EXPECTED_PRICES = Map.ofEntries(
            Map.entry("invoiceSettings", 0),
            Map.entry("autoBackup", 0),
            Map.entry("cafeMode", 0),
            Map.entry("fixedAssets", 0),
            Map.entry("simulatePosMode", 0),
            Map.entry("currencyExchange", 10),
            Map.entry("kitchenPrinter", 10),
            Map.entry("googleDriveBackup", 10),
            Map.entry("quotation", 15),
            Map.entry("userManagement", 20),
            Map.entry("deviceRepair", 20),
            Map.entry("shareholders", 20),
            Map.entry("accounting", 25),
            Map.entry("shifts", 25),
            Map.entry("productRecipes", 25),
            Map.entry("manufacturing", 25),
            Map.entry("tableOrders", 29),
            Map.entry("consignment", 29),
            Map.entry("posTerminals", 75),
            Map.entry("mobilePos", 25),
            // Not a feature flag: hosting the shop's Daman for them instead of it living on one
            // PC. Listed so its price is set on the same screen as every other price and
            // reaches the website from the same fetch. $100 is the figure the owner gave on
            // 2026-11-19; it is the SEED, and the live figure is the portal's to change.
            Map.entry("cloudHosting", 100));

    /**
     * The {@code price: null} rows in feature-catalog.ts. The owner hides these per client; they
     * are not products, so the modal never shows them and the catalog never carries them.
     */
    private static final List<String> UNPRICED_TOGGLES = List.of(
            "multiLanguage", "barcode", "reports", "suppliers", "multiCurrency",
            "clientLedger", "supplierLedger", "fractionalQuantity", "multiCurrencyPricing",
            "batchStocktake", "bulkPriceUpdate", "seedDemoData", "scaleBarcodes",
            "accountStatement", "itemLedger");

    private static Optional<FeatureCatalog.Entry> entry(String key) {
        return FeatureCatalog.ENTRIES.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    @Test
    void pricesMatchTheSpec() {
        EXPECTED_PRICES.forEach((key, price) ->
                assertThat(entry(key))
                        .as("catalog entry for %s", key)
                        .isPresent()
                        .get()
                        .extracting(FeatureCatalog.Entry::priceUsd)
                        .as("price of %s", key)
                        .isEqualTo(price));
    }

    @Test
    void everyEntryHasEitherAPriceOrContactUsButNotBoth() {
        for (FeatureCatalog.Entry e : FeatureCatalog.ENTRIES) {
            if (e.contactUs()) {
                assertThat(e.priceUsd()).as("%s is contact-us so carries no price", e.key()).isNull();
            } else {
                assertThat(e.priceUsd()).as("%s is priced so carries a price", e.key()).isNotNull();
            }
        }
    }

    @Test
    void newBranchIsPricedOnEnquiry() {
        assertThat(entry("newBranch")).isPresent().get()
                .extracting(FeatureCatalog.Entry::contactUs).isEqualTo(true);
    }

    @Test
    void manufacturingIsMarginalOnProductRecipes() {
        // Its $25 is the increment on top of Product Recipes' $25, so a client holding both
        // totals the $50 bundle rather than being double-charged. The modal must say so.
        assertThat(entry("manufacturing")).isPresent().get()
                .extracting(FeatureCatalog.Entry::bundledWith).isEqualTo("productRecipes");
    }

    @Test
    void onlyManufacturingIsBundled() {
        assertThat(FeatureCatalog.ENTRIES.stream()
                .filter(e -> e.bundledWith() != null)
                .map(FeatureCatalog.Entry::key))
                .containsExactly("manufacturing");
    }

    @Test
    void everyBundledWithNamesARealEntry() {
        Set<String> keys = new HashSet<>();
        FeatureCatalog.ENTRIES.forEach(e -> keys.add(e.key()));
        for (FeatureCatalog.Entry e : FeatureCatalog.ENTRIES) {
            if (e.bundledWith() != null) {
                assertThat(keys).as("prerequisite named by %s", e.key()).contains(e.bundledWith());
            }
        }
    }

    @Test
    void noEntryExistsForTheFifteenUnpricedToggles() {
        for (String key : UNPRICED_TOGGLES) {
            assertThat(entry(key)).as("%s must not be in the catalog", key).isEmpty();
        }
    }

    /**
     * The phone till and the Windows till are two products, not one with a discount. Neither
     * entitlement implies the other: a shop that paid $75 for POS Terminals does not get the
     * phone, and a shop that paid $25 for the phone cannot run a Windows terminal. Bundling
     * them would be a pricing decision, and {@code bundledWith} is how this catalog records
     * one — so the absence of it here is the decision, and this test pins it.
     */
    @Test
    void mobilePosIsItsOwnProduct_notBundledWithPosTerminals() {
        assertThat(entry("mobilePos")).isPresent().get()
                .extracting(FeatureCatalog.Entry::priceUsd).isEqualTo(25);
        assertThat(entry("mobilePos")).isPresent().get()
                .extracting(FeatureCatalog.Entry::bundledWith).isNull();
        assertThat(entry("posTerminals")).isPresent().get()
                .extracting(FeatureCatalog.Entry::bundledWith).isNull();
    }

    @Test
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (FeatureCatalog.Entry e : FeatureCatalog.ENTRIES) {
            assertThat(seen.add(e.key())).as("duplicate catalog key %s", e.key()).isTrue();
        }
    }

    @Test
    void holdsExactlyTheExpectedEntries() {
        // Guards against a row being added without a deliberate decision about its price.
        assertThat(FeatureCatalog.ENTRIES).hasSize(EXPECTED_PRICES.size() + 1); // + newBranch
    }
}
