package com.daman.admin.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrialPresetServiceTest {

    private final TrialSettingsService settings = mock(TrialSettingsService.class);
    private final TrialPresetService presets =
            new TrialPresetService(settings, JsonMapper.builder().build());

    private void given(String presetsJson) {
        when(settings.presetsJson()).thenReturn(presetsJson);
    }

    @Test
    void generalRetailGetsTheBaseSetOnly() {
        assertThat(presets.featuresFor("general", false))
                .isEqualTo(TrialPresetService.BASE_FEATURES);
    }

    @Test
    void cafeWithTablesStillDoesNotGetTableOrders() {
        // Table Orders is a $29 add-on. Owner, 2026-11-17: the paid modules must not be on
        // by default in a trial. A trial shows the product; it does not give it away.
        assertThat(presets.featuresFor("cafe", true)).containsEntry("tableOrders", false);
    }

    @Test
    void cafeWithoutTablesDoesNotGetTableOrders() {
        Map<String, Boolean> f = presets.featuresFor("cafe", false);
        assertThat(f).containsEntry("tableOrders", false);
        assertThat(f).containsEntry("cafeMode", true);
    }

    @Test
    void restaurantGetsNeitherTheKitchenPrinterNorRecipes() {
        // $10 and $25 respectively - both named by the owner.
        assertThat(presets.featuresFor("restaurant", true))
                .containsEntry("kitchenPrinter", false)
                .containsEntry("productRecipes", false)
                .containsEntry("tableOrders", false);
    }

    @Test
    void everyKnownStoreTypeResolves() {
        for (String t : TrialPresetService.KNOWN_STORE_TYPES) {
            assertThat(presets.featuresFor(t, true)).isNotEmpty();
        }
    }

    @Test
    void unknownStoreTypeFallsBackToBase() {
        assertThat(presets.featuresFor("taxidermist", true))
                .isEqualTo(TrialPresetService.BASE_FEATURES);
    }

    @Test
    void nullStoreTypeFallsBackToBase() {
        assertThat(presets.featuresFor(null, false)).isEqualTo(TrialPresetService.BASE_FEATURES);
    }

    @Test
    void malformedPresetOverrideFallsBackToBuiltIn() {
        given("{not json");
        assertThat(presets.featuresFor("cafe", true)).containsEntry("cafeMode", true);
    }

    @Test
    void emptyPresetOverrideFallsBackToBuiltIn() {
        given("{}");
        assertThat(presets.featuresFor("cafe", true)).containsEntry("cafeMode", true);
    }

    @Test
    void validOverrideIsLayeredOnBase() {
        // A FREE flag: an override still shapes a trial, it just cannot sell.
        given("{\"general\":{\"cafeMode\":true}}");
        assertThat(presets.featuresFor("general", false))
                .containsEntry("cafeMode", true)
                .containsEntry("reports", true);
        // a type the override does not mention keeps its built-in preset
        assertThat(presets.featuresFor("cafe", true)).containsEntry("cafeMode", true);
    }

    @Test
    void overrideCannotReintroduceQuickPickCards() {
        given("{\"cafe\":{\"quickPickCards\":true}}");
        assertThat(presets.featuresFor("cafe", true)).doesNotContainKey("quickPickCards");
    }

    @Test
    void quickPickCardsIsNotInAnyPreset() {
        assertThat(presets.featuresFor("cafe", true)).doesNotContainKey("quickPickCards");
        assertThat(TrialPresetService.BASE_FEATURES).doesNotContainKey("quickPickCards");
    }

    // ── No paid add-on is ever unlocked by a trial ──────────────────────────
    // Owner, 2026-11-17: "طابعة المطبخ، والصيانة، والمحاسبة والوصفات والتصنيع ما لازم
    // يكونو مفعلين بشكل افتراضي". The rule is taken from FeatureCatalog rather than from a
    // hand-written list, so a new add-on priced there is excluded from trials the day it is
    // priced - and a free feature (price 0) is unaffected.

    @Test
    void noPaidAddOnIsUnlockedForAnyShopType() {
        for (String type : TrialPresetService.KNOWN_STORE_TYPES) {
            Map<String, Boolean> f = presets.featuresFor(type, true);
            for (com.daman.admin.catalog.FeatureCatalog.Entry e : com.daman.admin.catalog.FeatureCatalog.ENTRIES) {
                boolean free = e.priceUsd() != null && e.priceUsd() == 0;
                if (free || !f.containsKey(e.key())) continue;
                assertThat(f.get(e.key()))
                        .as("%s trial must not include the paid %s", type, e.key())
                        .isFalse();
            }
        }
    }

    @Test
    void anOverrideCannotGiveAwayAPaidAddOn() {
        // The override is an admin setting, not a sale. This is also the only way a trial
        // could have been handing out Accounting, which no built-in preset grants.
        given("{\"cafe\":{\"accounting\":true,\"kitchenPrinter\":true,\"manufacturing\":true}}");
        assertThat(presets.featuresFor("cafe", true))
                .containsEntry("accounting", false)
                .containsEntry("kitchenPrinter", false)
                .containsEntry("manufacturing", false);
    }

    @Test
    void theFreeFeaturesAShopTypeImpliesAreStillGranted() {
        // The presets still exist and still do their job - cafeMode is free ($0 in the
        // catalog) and scale barcodes are an unpriced visibility switch.
        assertThat(presets.featuresFor("cafe", true)).containsEntry("cafeMode", true);
        assertThat(presets.featuresFor("supermarket", false)).containsEntry("scaleBarcodes", true);
    }

    @Test
    void baseCoversEveryLicenceFlagExceptQuickPickCards() {
        // 36 flags in ClientConfigExportDto.FeaturesDto minus quickPickCards.
        // (35 → 36 on 2026-11-15 when mobilePos, the phone till, was added.)
        assertThat(TrialPresetService.BASE_FEATURES).hasSize(35);
        // Named explicitly so a new flag that never reaches a trial is a failing
        // test rather than a silent gap: a trial that cannot see a feature is how
        // a shop decides it does not want it.
        assertThat(TrialPresetService.BASE_FEATURES).containsKey("mobilePos");
    }
}
