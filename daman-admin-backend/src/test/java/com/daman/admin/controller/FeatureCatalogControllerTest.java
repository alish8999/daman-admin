package com.daman.admin.controller;

import com.daman.admin.catalog.FeatureCatalog;
import com.daman.admin.dto.FeatureCatalogDto;
import com.daman.admin.entity.FeaturePrice;
import com.daman.admin.service.FeaturePriceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureCatalogControllerTest {

    private final FeaturePriceService prices = mock(FeaturePriceService.class);
    private final FeatureCatalogController controller = new FeatureCatalogController(prices);

    /** The stored rows, standing at the figures the catalogue seeds them with. */
    private void storedAtSeedPrices() {
        when(prices.all()).thenReturn(FeatureCatalog.ENTRIES.stream().map(e -> {
            FeaturePrice p = new FeaturePrice();
            p.setKey(e.key());
            p.setPriceUsd(e.priceUsd());
            p.setContactUs(e.contactUs());
            p.setBundledWith(e.bundledWith());
            return p;
        }).toList());
    }

    @Test
    void publicEndpointReturnsTheCatalogInUsd() {
        storedAtSeedPrices();

        FeatureCatalogDto body = controller.publicCatalog().getBody();

        assertThat(body).isNotNull();
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.features()).isNotEmpty();
    }

    @Test
    void bothEndpointsServeTheSameBody() {
        storedAtSeedPrices();

        assertThat(controller.catalog().getBody()).isSameAs(controller.publicCatalog().getBody());
    }

    @Test
    void repeatedCallsReuseOneCachedBody() {
        // The anonymous path is world-callable with no rate limiter in front of it, so a flood must
        // not cost a rebuild — or, now that prices are stored, a database round trip — per request.
        storedAtSeedPrices();

        assertThat(controller.publicCatalog().getBody()).isSameAs(controller.publicCatalog().getBody());
        verify(prices, times(1)).all();
    }

    @Test
    void googleDriveBackupIsCarriedAtTenDollars() {
        storedAtSeedPrices();

        assertThat(controller.publicCatalog().getBody().features())
                .filteredOn(f -> f.key().equals("googleDriveBackup"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.price()).isEqualTo(10);
                    assertThat(f.contactUs()).isFalse();
                });
    }

    @Test
    void newBranchIsCarriedAsContactUsWithNoPrice() {
        storedAtSeedPrices();

        assertThat(controller.publicCatalog().getBody().features())
                .filteredOn(f -> f.key().equals("newBranch"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.contactUs()).isTrue();
                    assertThat(f.price()).isNull();
                });
    }

    @Test
    void manufacturingCarriesItsPrerequisiteOnTheWire() {
        // Without bundledWith crossing the wire the POS would render a bare $25 that reads as the
        // whole cost of Manufacturing, when it is really the increment on top of Product Recipes.
        storedAtSeedPrices();

        assertThat(controller.publicCatalog().getBody().features())
                .filteredOn(f -> f.key().equals("manufacturing"))
                .singleElement()
                .extracting(FeatureCatalogDto.Feature::bundledWith)
                .isEqualTo("productRecipes");
    }

    // ── Editing ──────────────────────────────────────────────────────────────────

    private static FeatureCatalogController.PriceRequest request(Integer price, Boolean contactUs, String bundledWith) {
        var r = new FeatureCatalogController.PriceRequest();
        r.setPrice(price);
        r.setContactUs(contactUs);
        r.setBundledWith(bundledWith);
        return r;
    }

    @Test
    void aPriceIsSaved() {
        FeaturePrice saved = new FeaturePrice();
        saved.setKey("accounting");
        saved.setPriceUsd(30);
        when(prices.update(eq("accounting"), eq(30), eq(false), any())).thenReturn(saved);

        var response = controller.setPrice("accounting", request(30, false, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(prices).update("accounting", 30, false, null);
    }

    /**
     * The cache must not outlive the price it holds. A shop asking a minute after the owner
     * changed a figure would otherwise be quoted the old one until this backend restarted.
     */
    @Test
    void savingAPriceClearsTheCachedBody() {
        storedAtSeedPrices();
        FeatureCatalogDto before = controller.publicCatalog().getBody();
        when(prices.update(any(), any(), anyBooleanArg(), any())).thenReturn(new FeaturePrice());

        controller.setPrice("accounting", request(30, false, null));
        FeatureCatalogDto after = controller.publicCatalog().getBody();

        assertThat(after).isNotSameAs(before);
        verify(prices, times(2)).all();
    }

    /** A refusal from the service is a 400 with its reason, not a 500. */
    @Test
    void aRefusedPriceIsABadRequest() {
        when(prices.update(any(), any(), anyBooleanArg(), any()))
                .thenThrow(new IllegalArgumentException("a price cannot be negative"));

        var response = controller.setPrice("accounting", request(-1, false, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingBodyIsABadRequest() {
        assertThat(controller.setPrice("accounting", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A body with no contactUs flag means "not on enquiry", not null. */
    @Test
    void anAbsentContactUsFlagIsReadAsFalse() {
        when(prices.update(eq("accounting"), eq(25), eq(false), any())).thenReturn(new FeaturePrice());

        controller.setPrice("accounting", request(25, null, null));

        verify(prices).update("accounting", 25, false, null);
    }

    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
