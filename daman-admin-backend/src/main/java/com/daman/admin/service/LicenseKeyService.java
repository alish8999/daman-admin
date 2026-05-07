package com.daman.admin.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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

    private static final Path KEYS_DIR = Path.of(System.getProperty("user.home"), ".daman", "keys");
    private static final Path PRIVATE_KEY_PATH = KEYS_DIR.resolve("license-private.pem");
    private static final Path PUBLIC_KEY_PATH = KEYS_DIR.resolve("license-public.pem");

    private PrivateKey privateKey;
    private PublicKey publicKey;

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

    public String generateLicense(String machineId, String clientName, String clientCode, String expiresAt) {
        try {
            String payload = String.format(
                "{\"machineId\":\"%s\",\"clientName\":\"%s\",\"clientCode\":\"%s\",\"issuedAt\":\"%s\",\"expiresAt\":\"%s\"}",
                machineId, clientName, clientCode, LocalDate.now().toString(),
                expiresAt != null ? expiresAt : ""
            );

            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payloadBytes);
            byte[] signatureBytes = sig.sign();

            return Base64.getEncoder().encodeToString(payloadBytes) + "." + Base64.getEncoder().encodeToString(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate license", e);
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
