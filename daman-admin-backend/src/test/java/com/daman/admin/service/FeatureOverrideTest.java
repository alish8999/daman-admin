package com.daman.admin.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sparse per-licence feature override, in isolation.
 *
 * <p>Sparse is the whole point: an override holds only the keys that DIFFER from the client's
 * default, so a flag the owner later changes for the whole business still reaches a customised
 * licence. A full copy would freeze that licence at the moment it was customised and diverge
 * silently from then on.
 */
class FeatureOverrideTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseOfNullOrBlankIsEmpty() {
        assertThat(FeatureOverride.parse(null, mapper)).isEmpty();
        assertThat(FeatureOverride.parse("", mapper)).isEmpty();
        assertThat(FeatureOverride.parse("   ", mapper)).isEmpty();
    }

    /** A hand-edited column must read as "inherit everything", never throw mid-save or mid-mint. */
    @Test
    void parseOfMalformedJsonIsEmpty() {
        assertThat(FeatureOverride.parse("{not json", mapper)).isEmpty();
        assertThat(FeatureOverride.parse("[]", mapper)).isEmpty();
        assertThat(FeatureOverride.parse("\"accounting\"", mapper)).isEmpty();
        assertThat(FeatureOverride.parse("null", mapper)).isEmpty();
    }

    @Test
    void parseKeepsBothDirectionsAndDropsNulls() {
        Map<String, Boolean> m = FeatureOverride.parse(
                "{\"accounting\":true,\"shifts\":false,\"quotation\":null}", mapper);
        assertThat(m).containsEntry("accounting", true).containsEntry("shifts", false);
        assertThat(m).doesNotContainKey("quotation");
    }

    /** A non-boolean value is not an override either — it cannot say which way the flag goes. */
    @Test
    void parseDropsNonBooleanValues() {
        assertThat(FeatureOverride.parse("{\"accounting\":\"yes\",\"shifts\":1}", mapper)).isEmpty();
    }

    @Test
    void mergeInheritsOmittedKeysAndAppliesPresentOnes() {
        Map<String, Boolean> base = Map.of("accounting", true, "shifts", false, "quotation", true);
        Map<String, Boolean> merged = FeatureOverride.merge(base, Map.of("accounting", false, "shifts", true));
        assertThat(merged).containsEntry("accounting", false)
                          .containsEntry("shifts", true)
                          .containsEntry("quotation", true);
    }

    /** Guards against a retired or renamed flag resurrecting itself out of an old override. */
    @Test
    void mergeIgnoresOverrideKeysTheBaseDoesNotHave() {
        Map<String, Boolean> merged = FeatureOverride.merge(Map.of("accounting", true), Map.of("renamedFlag", true));
        assertThat(merged).containsOnlyKeys("accounting");
    }

    @Test
    void mergeOfAnEmptyOverrideIsTheBaseUnchanged() {
        Map<String, Boolean> base = Map.of("accounting", true, "shifts", false);
        assertThat(FeatureOverride.merge(base, Map.of())).isEqualTo(base);
    }

    @Test
    void mergeDoesNotMutateItsArguments() {
        Map<String, Boolean> base = new LinkedHashMap<>(Map.of("accounting", true));
        Map<String, Boolean> override = new LinkedHashMap<>(Map.of("accounting", false));
        FeatureOverride.merge(base, override);
        assertThat(base).containsEntry("accounting", true);
        assertThat(override).containsEntry("accounting", false);
    }

    @Test
    void writeOfEmptyMapIsNullSoTheColumnClears() {
        assertThat(FeatureOverride.write(Map.of(), mapper)).isNull();
        assertThat(FeatureOverride.write(null, mapper)).isNull();
    }

    @Test
    void writeIsKeySortedSoEqualOverridesSerialiseIdentically() {
        Map<String, Boolean> unsorted = new LinkedHashMap<>();
        unsorted.put("shifts", true);
        unsorted.put("accounting", false);
        assertThat(FeatureOverride.write(unsorted, mapper))
                .isEqualTo("{\"accounting\":false,\"shifts\":true}");
    }

    @Test
    void writeThenParseRoundTrips() {
        Map<String, Boolean> original = new LinkedHashMap<>();
        original.put("consignment", true);
        original.put("multiCurrencyPricing", false);
        assertThat(FeatureOverride.parse(FeatureOverride.write(original, mapper), mapper))
                .isEqualTo(original);
    }
}
