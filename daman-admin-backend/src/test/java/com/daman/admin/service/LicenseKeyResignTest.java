package com.daman.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-signing a licence with a new feature set, leaving every other payload field alone.
 *
 * <p><b>Why this exists instead of just calling {@code generateLicense} again.</b> A fresh mint
 * rebuilds the payload from the client's current record, so a feature save would also push any
 * branding, contact, store-type or base-currency edit made since the key was issued — an
 * unrelated change arriving under a feature push. Worse, {@code generateLicense}'s {@code trial}
 * parameter defaults to false and emits nothing, so a fresh mint would <b>drop
 * {@code trial: true}</b> from a live trial's key, and the desktop reads that flag for its
 * lock-screen wording. {@code LicenseReissueService} already drops it today; that is a bug to
 * avoid repeating, not a precedent to follow.
 *
 * <p>Follows {@code LicenseKeyServiceTest}'s setup: the real service against the real key
 * directory, reusing an existing pair when there is one.
 */
class LicenseKeyResignTest {

    LicenseKeyService service;

    @BeforeEach
    void setUp() {
        service = new LicenseKeyService();
        service.init();
    }

    private String fullKey(boolean trial) {
        return service.generateLicense(
                "MACHINE-1", "Shop", "c-1", "2027-01-01", "SYP",
                Map.of("accounting", false, "consignment", true),
                "#111111", "#222222", "admin", "pw",
                new LicenseProfile("Shop", "0999", "Addr", "grocery", true), trial);
    }

    @Test
    void resignReplacesFeaturesAndNothingElse() {
        String original = fullKey(false);
        String resigned = service.resignWithFeatures(original, Map.of("accounting", true, "consignment", false));

        JsonNode before = service.verifiedPayload(original);
        JsonNode after = service.verifiedPayload(resigned);
        assertThat(after).isNotNull();

        assertThat(after.path("features").path("accounting").asBoolean()).isTrue();
        assertThat(after.path("features").path("consignment").asBoolean()).isFalse();

        for (String field : List.of("v", "machineId", "clientName", "clientCode", "issuedAt",
                                    "expiresAt", "baseCurrency", "colors", "credentials", "profile")) {
            assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
        }
    }

    /** The reason a narrow re-sign exists at all. */
    @Test
    void resignPreservesTheTrialFlag() {
        String resigned = service.resignWithFeatures(fullKey(true), Map.of("accounting", true));
        assertThat(service.verifiedPayload(resigned).path("trial").asBoolean()).isTrue();
    }

    /** And must not invent one on a paid licence. */
    @Test
    void resignDoesNotAddATrialFlagToAPaidLicence() {
        String resigned = service.resignWithFeatures(fullKey(false), Map.of("accounting", true));
        assertThat(service.verifiedPayload(resigned).has("trial")).isFalse();
    }

    @Test
    void theResignedKeyVerifiesAndKeepsTheTwoPartShape() {
        String resigned = service.resignWithFeatures(fullKey(false), Map.of("accounting", true));
        assertThat(resigned.split("\\.")).hasSize(2);
        assertThat(service.verifiedPayload(resigned)).isNotNull();
        assertThat(service.payloadVersion(resigned)).isEqualTo(2);
    }

    /** A hand-edited or truncated key must never be re-signed into a valid one. */
    @Test
    void resignOfATamperedKeyReturnsNull() {
        String key = fullKey(false);
        int dot = key.indexOf('.');
        String tampered = key.substring(0, dot - 4) + "AAAA" + key.substring(dot);
        assertThat(service.resignWithFeatures(tampered, Map.of("accounting", true))).isNull();
    }

    @Test
    void resignOfGarbageOrNullReturnsNull() {
        assertThat(service.resignWithFeatures(null, Map.of())).isNull();
        assertThat(service.resignWithFeatures("", Map.of())).isNull();
        assertThat(service.resignWithFeatures("not-a-key", Map.of())).isNull();
        assertThat(service.resignWithFeatures("a.b", Map.of())).isNull();
    }

    @Test
    void resignWithAnEmptyFeatureMapWritesAnEmptyFeaturesObject() {
        String resigned = service.resignWithFeatures(fullKey(false), Map.of());
        JsonNode after = service.verifiedPayload(resigned);
        assertThat(after).isNotNull();
        assertThat(after.path("features").isObject()).isTrue();
        assertThat(after.path("features").isEmpty()).isTrue();
    }

    /** A v1 key has no features object; re-signing one must still produce a verifiable key. */
    @Test
    void resignAddsAFeaturesObjectWhenThePayloadHadNone() {
        String noFeatures = service.generateLicense("MACHINE-2", "Shop", "c-2", null, "SYP", Map.of());
        String resigned = service.resignWithFeatures(noFeatures, Map.of("accounting", true));
        assertThat(service.verifiedPayload(resigned).path("features").path("accounting").asBoolean()).isTrue();
    }
}
