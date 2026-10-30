package com.daman.admin.catalog;

import java.util.List;

/**
 * The single source of truth for add-on prices, for both the admin portal and every shipped POS.
 *
 * <p><b>Why this exists.</b> Until 2026-10-30 the same prices were written in three places that
 * had already drifted apart: the POS upsell modal's markup (Accounting $39, Shifts $29, Recipes
 * $39), the per-card comments on the POS features page (Accounting $25, Manufacturing $50, POS
 * Terminals $100/$75), and {@code feature-catalog.ts}'s own {@code price} field. The figures here
 * are {@code feature-catalog.ts}'s, verbatim: it was the only complete and internally consistent
 * list, and every point where it disagreed with the POS modal it was *lower*, so adopting it never
 * raises a price a shop owner has already been quoted.
 *
 * <p><b>Changing a price</b> means editing this file and redeploying this backend. The owner
 * accepted that in place of an admin CRUD screen, so there is deliberately no database table here
 * and no migration. Shipped POS installs pick the change up on their next catalog fetch — they do
 * not need a new build.
 *
 * <p><b>What is NOT here.</b> The fifteen {@code price: null} rows of {@code feature-catalog.ts}
 * (reports, accountStatement, itemLedger, batchStocktake, barcode, bulkPriceUpdate, multiLanguage,
 * suppliers, multiCurrency, clientLedger, supplierLedger, fractionalQuantity, multiCurrencyPricing,
 * seedDemoData, scaleBarcodes). Those are per-client visibility switches the owner turns off for
 * some clients: never sold, never priced, and — since 2026-10-30 — never advertised in the POS
 * either. A row for one of them would be dead data a later reader could mistake for a product.
 */
public final class FeatureCatalog {

    private FeatureCatalog() { }

    /**
     * One sellable item.
     *
     * @param key         matches a boolean key on {@code ClientFeatures}, except {@code newBranch},
     *                    which is not a feature flag at all — see the class note on
     *                    {@code CatalogKey} in the POS frontend.
     * @param priceUsd    USD price, or null exactly when {@code contactUs} is true. {@code 0} means
     *                    free and included, which is NOT the same as absent.
     * @param contactUs   priced on enquiry; the modal shows "contact us" instead of a figure.
     * @param bundledWith the key of a prerequisite whose price this one is the increment ON TOP OF,
     *                    or null when the price stands alone. See {@code manufacturing}.
     */
    public record Entry(String key, Integer priceUsd, boolean contactUs, String bundledWith) {

        static Entry priced(String key, int priceUsd) {
            return new Entry(key, priceUsd, false, null);
        }

        static Entry marginal(String key, int priceUsd, String bundledWith) {
            return new Entry(key, priceUsd, false, bundledWith);
        }

        static Entry onEnquiry(String key) {
            return new Entry(key, null, true, null);
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            // Free and included. Listed rather than omitted so the features page can say "Free"
            // from the same source everything else comes from.
            Entry.priced("invoiceSettings", 0),
            Entry.priced("autoBackup", 0),
            Entry.priced("cafeMode", 0),
            Entry.priced("fixedAssets", 0),
            Entry.priced("simulatePosMode", 0),

            Entry.priced("currencyExchange", 10),
            Entry.priced("kitchenPrinter", 10),
            Entry.priced("googleDriveBackup", 10),
            Entry.priced("quotation", 15),
            Entry.priced("userManagement", 20),
            Entry.priced("deviceRepair", 20),
            Entry.priced("shareholders", 20),
            Entry.priced("accounting", 25),
            Entry.priced("shifts", 25),
            Entry.priced("productRecipes", 25),

            // Manufacturing needs Product Recipes (BOM) to function, so $25 is the MARGINAL cost on
            // top of BOM's $25: a client holding both totals the $50 bundle rather than paying
            // $25+$25 twice over. Anything that sums these prices depends on that, and the POS modal
            // names the prerequisite rather than showing a bare $25 that reads as the whole cost.
            Entry.marginal("manufacturing", 25, "productRecipes"),

            Entry.priced("tableOrders", 29),
            Entry.priced("consignment", 29),
            Entry.priced("posTerminals", 75),

            // Not a ClientFeatures flag — there is no `newBranch` boolean anywhere. It exists here
            // because the POS features page has a real New Branch card that opens the modal, and
            // the modal needs something to render. Priced per deal, so: contact us.
            Entry.onEnquiry("newBranch"));
}
