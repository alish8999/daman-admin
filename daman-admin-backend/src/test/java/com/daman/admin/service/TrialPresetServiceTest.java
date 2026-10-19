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
    void cafeWithTablesGetsTableOrders() {
        assertThat(presets.featuresFor("cafe", true)).containsEntry("tableOrders", true);
    }

    @Test
    void cafeWithoutTablesDoesNotGetTableOrders() {
        Map<String, Boolean> f = presets.featuresFor("cafe", false);
        assertThat(f).containsEntry("tableOrders", false);
        assertThat(f).containsEntry("cafeMode", true);
    }

    @Test
    void restaurantWithoutTablesStillGetsKitchenPrinter() {
        assertThat(presets.featuresFor("restaurant", false))
                .containsEntry("kitchenPrinter", true)
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
        given("{\"general\":{\"accounting\":true}}");
        assertThat(presets.featuresFor("general", false))
                .containsEntry("accounting", true)
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

    @Test
    void baseCoversEveryLicenceFlagExceptQuickPickCards() {
        // 34 flags in ClientConfigExportDto.FeaturesDto minus quickPickCards.
        assertThat(TrialPresetService.BASE_FEATURES).hasSize(33);
    }
}
