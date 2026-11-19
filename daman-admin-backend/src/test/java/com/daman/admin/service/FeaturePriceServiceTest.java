package com.daman.admin.service;

import com.daman.admin.catalog.FeatureCatalog;
import com.daman.admin.entity.FeaturePrice;
import com.daman.admin.repository.FeaturePriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The add-on prices, now a table the owner edits rather than a constant in Java.
 *
 * <p>The rules worth pinning are the ones that keep an unsellable row out of a shop's screen.
 * A price goes straight onto every till's features page and into the upsell modal, and the
 * modal exists to start a conversation rather than to quote — so a figure that is wrong is
 * worse than a figure that is missing, and a key nothing can sell must never acquire one.
 */
class FeaturePriceServiceTest {

    private FeaturePriceRepository repository;
    private FeaturePriceService service;

    @BeforeEach
    void setUp() {
        repository = mock(FeaturePriceRepository.class);
        service = new FeaturePriceService(repository);
    }

    private static FeaturePrice row(String key, Integer price, boolean contactUs, String bundledWith) {
        FeaturePrice p = new FeaturePrice();
        p.setKey(key);
        p.setPriceUsd(price);
        p.setContactUs(contactUs);
        p.setBundledWith(bundledWith);
        return p;
    }

    // ── Seeding ──────────────────────────────────────────────────────────────────

    /**
     * First boot: the table is empty and the catalogue's own figures become its contents, so
     * nothing a shop sees changes on the day this ships.
     */
    @Test
    void anEmptyTableIsSeededFromTheCatalogue() {
        when(repository.findAll()).thenReturn(List.of());
        List<FeaturePrice> saved = captureSaveAll();

        service.seedMissingRows();

        assertThat(saved).hasSize(FeatureCatalog.ENTRIES.size());
        FeatureCatalog.Entry accounting = FeatureCatalog.ENTRIES.stream()
                .filter(e -> e.key().equals("accounting")).findFirst().orElseThrow();
        assertThat(saved).anySatisfy(p -> {
            assertThat(p.getKey()).isEqualTo("accounting");
            assertThat(p.getPriceUsd()).isEqualTo(accounting.priceUsd());
        });
    }

    /**
     * The owner's edits survive every later deploy. Re-seeding an existing key would silently
     * reset a price back to whatever the source file happened to say, which is the one thing
     * this whole change exists to stop.
     */
    @Test
    void aRowTheOwnerHasAlreadyEditedIsNeverOverwritten() {
        when(repository.findAll()).thenReturn(List.of(row("accounting", 99, false, null)));
        List<FeaturePrice> saved = captureSaveAll();

        service.seedMissingRows();

        assertThat(saved).noneMatch(p -> p.getKey().equals("accounting"));
    }

    /** A newly sellable add-on appears on its own, priced as the catalogue proposes. */
    @Test
    void aCatalogueKeyWithNoRowYetIsAdded() {
        List<FeaturePrice> existing = FeatureCatalog.ENTRIES.stream()
                .filter(e -> !e.key().equals("mobilePos"))
                .map(e -> row(e.key(), e.priceUsd(), e.contactUs(), e.bundledWith()))
                .toList();
        when(repository.findAll()).thenReturn(existing);
        List<FeaturePrice> saved = captureSaveAll();

        service.seedMissingRows();

        assertThat(saved).singleElement().satisfies(p -> assertThat(p.getKey()).isEqualTo("mobilePos"));
    }

    @Test
    void seedingWritesNothingWhenEveryKeyAlreadyHasARow() {
        when(repository.findAll()).thenReturn(FeatureCatalog.ENTRIES.stream()
                .map(e -> row(e.key(), e.priceUsd(), e.contactUs(), e.bundledWith()))
                .toList());

        service.seedMissingRows();

        verify(repository, never()).saveAll(any());
    }

    // ── Editing ──────────────────────────────────────────────────────────────────

    @Test
    void aPriceIsUpdated() {
        when(repository.findById("accounting")).thenReturn(Optional.of(row("accounting", 25, false, null)));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        FeaturePrice updated = service.update("accounting", 30, false, null);

        assertThat(updated.getPriceUsd()).isEqualTo(30);
        assertThat(updated.isContactUs()).isFalse();
    }

    /** Free is a price, not an absence — the features page says "Free" from this very row. */
    @Test
    void zeroIsAValidPrice() {
        when(repository.findById("autoBackup")).thenReturn(Optional.of(row("autoBackup", 0, false, null)));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.update("autoBackup", 0, false, null).getPriceUsd()).isZero();
    }

    @Test
    void contactUsClearsTheFigureRatherThanKeepingAStaleOne() {
        when(repository.findById("newBranch")).thenReturn(Optional.of(row("newBranch", 500, false, null)));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        FeaturePrice updated = service.update("newBranch", 500, true, null);

        assertThat(updated.isContactUs()).isTrue();
        assertThat(updated.getPriceUsd()).as("a figure left behind would be shown by something").isNull();
    }

    @Test
    void aPricedRowMustActuallyHaveAPrice() {
        when(repository.findById("accounting")).thenReturn(Optional.of(row("accounting", 25, false, null)));

        assertThatThrownBy(() -> service.update("accounting", null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aNegativePriceIsRefused() {
        when(repository.findById("accounting")).thenReturn(Optional.of(row("accounting", 25, false, null)));

        assertThatThrownBy(() -> service.update("accounting", -5, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    /** The catalogue decides what is sellable; a row for anything else is not a product. */
    @Test
    void aKeyTheCatalogueDoesNotKnowCannotBePriced() {
        assertThatThrownBy(() -> service.update("notAProduct", 10, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aPrerequisiteMustItselfBeAKnownAddOn() {
        when(repository.findById("manufacturing")).thenReturn(Optional.of(row("manufacturing", 25, false, "productRecipes")));

        assertThatThrownBy(() -> service.update("manufacturing", 25, false, "somethingElse"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    /** A row bundled with itself would make its own price infinite to read and impossible to sum. */
    @Test
    void anAddOnCannotBeBundledWithItself() {
        when(repository.findById("manufacturing")).thenReturn(Optional.of(row("manufacturing", 25, false, "productRecipes")));

        assertThatThrownBy(() -> service.update("manufacturing", 25, false, "manufacturing"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    private List<FeaturePrice> captureSaveAll() {
        List<FeaturePrice> saved = new ArrayList<>();
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            ((Iterable<FeaturePrice>) invocation.getArgument(0)).forEach(saved::add);
            return saved;
        });
        return saved;
    }
}
