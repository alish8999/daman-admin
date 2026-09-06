package com.daman.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseKeyServiceTest {

    LicenseKeyService service;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // init() reads/creates ~/.daman/keys — acceptable for a local test run.
        // If key files already exist they are reused; otherwise a pair is generated.
        service = new LicenseKeyService();
        service.init();
    }

    @Test
    void generateLicense_v2_payloadCarriesVersionBaseCurrencyAndFeatures_andSignatureVerifies() throws Exception {
        Map<String, Boolean> features = Map.of("posTerminals", true, "accounting", true, "barcode", false);

        String license = service.generateLicense(
                "MACHINE-1", "Acme", "acme", "2027-01-01", "SYP", features);

        String[] parts = license.split("\\.");
        assertThat(parts).hasSize(2);

        byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
        byte[] sigBytes     = Base64.getDecoder().decode(parts[1]);

        // Signature verifies against the service's own public key
        String pem = service.getPublicKeyPem()
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(pub);
        sig.update(payloadBytes);
        assertThat(sig.verify(sigBytes)).isTrue();

        JsonNode payload = mapper.readTree(payloadBytes);
        assertThat(payload.path("v").asInt()).isEqualTo(2);
        assertThat(payload.path("machineId").asText("")).isEqualTo("MACHINE-1");
        assertThat(payload.path("clientCode").asText("")).isEqualTo("acme");
        assertThat(payload.path("expiresAt").asText("")).isEqualTo("2027-01-01");
        assertThat(payload.path("baseCurrency").asText("")).isEqualTo("SYP");
        assertThat(payload.path("features").path("posTerminals").asBoolean()).isTrue();
        assertThat(payload.path("features").path("accounting").asBoolean()).isTrue();
        assertThat(payload.path("features").path("barcode").asBoolean()).isFalse();
    }

    @Test
    void generateLicense_nullBaseCurrencyAndFeatures_omitsThoseKeys_stillV2() throws Exception {
        String license = service.generateLicense("M", "N", "c", null, null, null);
        JsonNode payload = mapper.readTree(Base64.getDecoder().decode(license.split("\\.")[0]));
        assertThat(payload.path("v").asInt()).isEqualTo(2);
        assertThat(payload.has("baseCurrency")).isFalse();
        assertThat(payload.has("features")).isFalse();
        assertThat(payload.path("expiresAt").asText("")).isEmpty();
    }

    @Test
    void payloadVersion_v2Key_returns2() {
        String key = service.generateLicense(
                "MACHINE-1", "Acme", "acme", "2027-01-01", "SYP", Map.of("barcode", true));
        assertThat(service.payloadVersion(key)).isEqualTo(2);
    }

    @Test
    void payloadVersion_v1StyleKey_returns1() {
        // A v1 payload has no "v" field. The signature half is irrelevant to payloadVersion().
        String payloadB64 = Base64.getEncoder().encodeToString(
                "{\"machineId\":\"M1\",\"clientCode\":\"acme\",\"expiresAt\":\"\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(service.payloadVersion(payloadB64 + ".irrelevant-signature")).isEqualTo(1);
    }

    @Test
    void payloadVersion_garbage_returns1() {
        assertThat(service.payloadVersion("not-a-real-key")).isEqualTo(1);
        assertThat(service.payloadVersion("")).isEqualTo(1);
    }
}
