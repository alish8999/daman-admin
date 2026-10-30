package com.daman.admin.controller;

import com.daman.admin.dto.FeatureCatalogDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureCatalogControllerTest {

    private final FeatureCatalogController controller = new FeatureCatalogController();

    @Test
    void publicEndpointReturnsTheCatalogInUsd() {
        FeatureCatalogDto body = controller.publicCatalog().getBody();
        assertThat(body).isNotNull();
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.features()).isNotEmpty();
    }

    @Test
    void bothEndpointsServeTheSameBody() {
        assertThat(controller.catalog().getBody()).isSameAs(controller.publicCatalog().getBody());
    }

    @Test
    void repeatedCallsReuseOneCachedBody() {
        // The anonymous path is world-callable with no rate limiter in front of it, so a flood must
        // not cost a rebuild per request. Identity across calls is what proves that.
        assertThat(controller.publicCatalog().getBody()).isSameAs(controller.publicCatalog().getBody());
    }

    @Test
    void googleDriveBackupIsCarriedAtTenDollars() {
        assertThat(controller.publicCatalog().getBody().features())
                .anySatisfy(f -> {
                    assertThat(f.key()).isEqualTo("googleDriveBackup");
                    assertThat(f.price()).isEqualTo(10);
                    assertThat(f.contactUs()).isFalse();
                });
    }

    @Test
    void newBranchIsCarriedAsContactUsWithNoPrice() {
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
        assertThat(controller.publicCatalog().getBody().features())
                .filteredOn(f -> f.key().equals("manufacturing"))
                .singleElement()
                .extracting(FeatureCatalogDto.Feature::bundledWith)
                .isEqualTo("productRecipes");
    }
}
