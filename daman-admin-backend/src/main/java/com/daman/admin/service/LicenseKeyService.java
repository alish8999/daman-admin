package com.daman.admin.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

@Slf4j
@Service
public class LicenseKeyService {

    private static final Path KEYS_DIR = Path.of(System.getProperty("user.home"), "daman_admin", "keys");
    private static final Path PRIVATE_KEY_PATH = KEYS_DIR.resolve("license-private.pem");
    private static final Path PUBLIC_KEY_PATH = KEYS_DIR.resolve("license-public.pem");

    private PrivateKey privateKey;
    private PublicKey publicKey;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(KEYS_DIR);
            if (Files.exists(PRIVATE_KEY_PATH) && Files.exists(PUBLIC_KEY_PATH)) {
                loadKeys();
                log.info("License RSA key pair loaded from {}", KEYS_DIR);
            } else {
                generateAndSaveKeys();
                log.info("License RSA key pair generated and saved to {}", KEYS_DIR);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize license key pair", e);
        }
    }

    private void generateAndSaveKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        this.privateKey = kp.getPrivate();
        this.publicKey = kp.getPublic();

        Files.writeString(PRIVATE_KEY_PATH, toPem("PRIVATE KEY", privateKey.getEncoded()));
        Files.writeString(PUBLIC_KEY_PATH, toPem("PUBLIC KEY", publicKey.getEncoded()));
    }

    private void loadKeys() throws Exception {
        String privatePem = Files.readString(PRIVATE_KEY_PATH);
        String publicPem = Files.readString(PUBLIC_KEY_PATH);

        byte[] privateBytes = fromPem(privatePem);
        byte[] publicBytes = fromPem(publicPem);

        KeyFactory kf = KeyFactory.getInstance("RSA");
        this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        this.publicKey = kf.generatePublic(new X509EncodedKeySpec(publicBytes));
    }

    public String generateLicense(String machineId, String clientName, String clientCode,
                                  String expiresAt, String baseCurrency,
                                  java.util.Map<String, Boolean> features) {
        return generateLicense(machineId, clientName, clientCode, expiresAt, baseCurrency, features, null, null, null, null);
    }

    private static void putIfNotBlank(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.put(key, value.trim());
    }

    public String generateLicense(String machineId, String clientName, String clientCode,
                                  String expiresAt, String baseCurrency,
                                  java.util.Map<String, Boolean> features,
                                  String colorPrimary, String colorSecondary) {
        return generateLicense(machineId, clientName, clientCode, expiresAt, baseCurrency, features,
                colorPrimary, colorSecondary, null, null);
    }

    public String generateLicense(String machineId, String clientName, String clientCode,
                                  String expiresAt, String baseCurrency,
                                  java.util.Map<String, Boolean> features,
                                  String colorPrimary, String colorSecondary,
                                  String adminUsername, String adminPassword) {
        return generateLicense(machineId, clientName, clientCode, expiresAt, baseCurrency, features,
                colorPrimary, colorSecondary, adminUsername, adminPassword, null);
    }

    public String generateLicense(String machineId, String clientName, String clientCode,
                                  String expiresAt, String baseCurrency,
                                  java.util.Map<String, Boolean> features,
                                  String colorPrimary, String colorSecondary,
                                  String adminUsername, String adminPassword,
                                  LicenseProfile profile) {
        return generateLicense(machineId, clientName, clientCode, expiresAt, baseCurrency, features,
                colorPrimary, colorSecondary, adminUsername, adminPassword, profile, false);
    }

    /**
     * {@code trial} true emits {@code "trial": true}; false emits nothing (never {@code "trial": false}).
     * Only TrialRegistrationService passes true. Conversion mints a NEW key through the ordinary paid
     * path, which never sets it, so the flag vanishes on conversion with no clearing logic anywhere.
     */
    public String generateLicense(String machineId, String clientName, String clientCode,
                                  String expiresAt, String baseCurrency,
                                  java.util.Map<String, Boolean> features,
                                  String colorPrimary, String colorSecondary,
                                  String adminUsername, String adminPassword,
                                  LicenseProfile profile, boolean trial) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("v", 2);
            payload.put("machineId", machineId);
            payload.put("clientName", clientName);
            payload.put("clientCode", clientCode);
            payload.put("issuedAt", LocalDate.now().toString());
            payload.put("expiresAt", expiresAt != null ? expiresAt : "");
            if (baseCurrency != null && !baseCurrency.isBlank()) {
                payload.put("baseCurrency", baseCurrency);
            }
            if (features != null && !features.isEmpty()) {
                ObjectNode f = payload.putObject("features");
                features.forEach(f::put);
            }
            // Per-client brand colours for the generic build (v3-ish additive field —
            // older desktop code just ignores unknown keys). Only the two clients
            // actually customise; success/danger/warning/info stay at build defaults.
            boolean hasPrimary   = colorPrimary   != null && !colorPrimary.isBlank();
            boolean hasSecondary = colorSecondary != null && !colorSecondary.isBlank();
            if (hasPrimary || hasSecondary) {
                ObjectNode colors = payload.putObject("colors");
                if (hasPrimary)   colors.put("primary", colorPrimary.trim());
                if (hasSecondary) colors.put("secondary", colorSecondary.trim());
            }
            // Default admin login to seed on first activation of a generic build
            // (additive field, same as colors above — ignored by older desktop
            // code). Only seeded when the client has no matching user yet; see
            // LicenseService.activate() on the desktop side.
            boolean hasUsername = adminUsername != null && !adminUsername.isBlank();
            boolean hasPassword = adminPassword != null && !adminPassword.isBlank();
            if (hasUsername && hasPassword) {
                ObjectNode credentials = payload.putObject("credentials");
                credentials.put("username", adminUsername.trim());
                credentials.put("password", adminPassword);
            }
            // Business profile (additive, ignored by older desktop code). Emitted only when at
            // least one field is non-blank, and then only the non-blank ones — legacy client
            // rows have nulls and must never produce "phone": null.
            if (profile != null) {
                ObjectNode p = mapper.createObjectNode();
                putIfNotBlank(p, "appName", profile.appName());
                putIfNotBlank(p, "phone", profile.phone());
                putIfNotBlank(p, "address", profile.address());
                putIfNotBlank(p, "storeType", profile.storeType());
                if (profile.hasTables() != null) p.put("hasTables", profile.hasTables().booleanValue());
                if (!p.isEmpty()) payload.set("profile", p);
            }
            // Trial marker (additive). true only for a trial mint; absent otherwise, never false.
            if (trial) payload.put("trial", true);
            byte[] payloadBytes = mapper.writeValueAsBytes(payload);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payloadBytes);
            byte[] signatureBytes = sig.sign();

            return Base64.getEncoder().encodeToString(payloadBytes) + "."
                 + Base64.getEncoder().encodeToString(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate license", e);
        }
    }

    /**
     * Verifies a licence key's RSA signature and returns its payload, or {@code null} if the
     * signature does not check out, the key is malformed, or the payload is not JSON.
     *
     * <p>This is a PROOF OF POSSESSION primitive, not an authorisation decision: it says only
     * "this key was minted by us and has not been edited". Expiry, revocation and status are
     * deliberately NOT considered — the renewal endpoint has to accept an expired key, since
     * renewing one is the entire point. Never throws.
     */
    public JsonNode verifiedPayload(String licenseKey) {
        try {
            if (licenseKey == null || licenseKey.isBlank() || publicKey == null) return null;
            String[] parts = licenseKey.trim().split("\\.");
            if (parts.length != 2) return null;
            byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
            byte[] signatureBytes = Base64.getDecoder().decode(parts[1]);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(payloadBytes);
            if (!sig.verify(signatureBytes)) return null;

            return mapper.readTree(payloadBytes);
        } catch (Exception e) {
            // A bad base64 chunk, a truncated key, a non-JSON payload: all just "not ours".
            return null;
        }
    }

    /**
     * The {@code "v"} field of a licence key's base64 payload half, or {@code 1}
     * for a v1 key (which has no {@code "v"}) or anything unparseable. Drives the
     * v1/v2 badge in the admin licence list and labels already-migrated rows in the
     * Stage-1 re-issue preview. Never throws.
     */
    public int payloadVersion(String licenseKey) {
        try {
            String payloadB64 = licenseKey.split("\\.", 2)[0];
            byte[] payload = Base64.getDecoder().decode(payloadB64);
            JsonNode root = mapper.readTree(payload);
            return root.path("v").asInt(1);
        } catch (Exception e) {
            return 1;
        }
    }

    public String getPublicKeyPem() {
        return toPem("PUBLIC KEY", publicKey.getEncoded());
    }

    private String toPem(String type, byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    private byte[] fromPem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
