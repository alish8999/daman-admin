package com.daman.admin.service;

import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.dto.TrialRegisterResponse;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
import com.daman.admin.entity.TrialRegistration;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Self-serve trial registration behind the ANONYMOUS {@code POST /api/trial/register}.
 *
 * <p>Safe to expose because: the caller never names a client code (we generate {@code trial-N}),
 * the licence is bound to the caller's own machine id, expiry comes from server settings, the
 * response is the licence key only, and one machine id maps to one registration (unique index).
 *
 * <p>Order matters: everything the licence needs is computed and the key is MINTED before a
 * single row is written, so a failed mint leaves no trace by construction. The three saves then
 * run in one transaction as defence in depth. The transaction is a {@link TransactionTemplate}
 * (not {@code @Transactional}) so the unique-violation of a lost race surfaces HERE, after
 * rollback, where we can re-read the winner's committed rows instead of dying on an aborted
 * transaction.
 */
@Slf4j
@Service
public class TrialRegistrationService {

    static final String CODE_PREFIX = "trial-";
    static final String LABEL = "TRIAL";
    static final int MAX_MACHINE_ID = 128;
    static final int MAX_SHOP_NAME = 100;
    static final int MAX_PHONE = 50;
    static final int MAX_ADDRESS = 255;
    static final int MAX_DEVICE_INFO = 255;
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> CURRENCIES = Set.of("USD", "SYP");
    private static final String PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PW_LENGTH = 16;
    private static final String DEFAULT_PRIMARY = "#667eea";
    private static final String DEFAULT_SECONDARY = "#764ba2";

    private final TrialSettingsService settings;
    private final TrialPresetService presets;
    private final LicenseKeyService licenseKeyService;
    private final ClientConfigRepository clients;
    private final TrialRegistrationRepository trials;
    private final LicenseRepository licenses;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public TrialRegistrationService(TrialSettingsService settings, TrialPresetService presets,
                                    LicenseKeyService licenseKeyService, ClientConfigRepository clients,
                                    TrialRegistrationRepository trials, LicenseRepository licenses,
                                    TransactionTemplate tx) {
        this.settings = settings;
        this.presets = presets;
        this.licenseKeyService = licenseKeyService;
        this.clients = clients;
        this.trials = trials;
        this.licenses = licenses;
        this.tx = tx;
    }

    public TrialRegisterResponse register(TrialRegisterRequest req, String machineId, String deviceInfo) {
        if (!settings.enabled()) {
            throw new TrialsDisabledException();
        }
        if (req == null) {
            throw new IllegalArgumentException("request required");
        }
        if (machineId == null || machineId.isBlank() || machineId.length() > MAX_MACHINE_ID) {
            throw new IllegalArgumentException("invalid machineId");
        }
        String machine = machineId.trim();

        Optional<TrialRegistration> existing = trials.findByMachineId(machine);
        if (existing.isPresent()) {
            return existingKey(existing.get());
        }

        // Validate + normalise before anything is minted or written.
        String shopName = truncate(req.shopName(), MAX_SHOP_NAME);
        if (shopName == null || shopName.isBlank()) {
            throw new IllegalArgumentException("shopName required");
        }
        String currency = req.baseCurrency() == null ? "" : req.baseCurrency().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("invalid baseCurrency");
        }
        String storeType = req.storeType() == null ? "" : req.storeType().trim().toLowerCase(Locale.ROOT);
        if (!TrialPresetService.KNOWN_STORE_TYPES.contains(storeType)) {
            throw new IllegalArgumentException("invalid storeType");
        }
        boolean hasTables = Boolean.TRUE.equals(req.hasTables());
        String phone = truncate(req.phone(), MAX_PHONE);
        String address = truncate(req.address(), MAX_ADDRESS);
        String device = truncate(deviceInfo, MAX_DEVICE_INFO);

        LocalDate expiryDate = LocalDate.now().plusDays(settings.days());
        Map<String, Boolean> features = presets.featuresFor(storeType, hasTables);
        LicenseProfile profile = new LicenseProfile(shopName, phone, address, storeType, hasTables);

        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String clientCode = nextClientCode();
            String adminPassword = generatePassword();

            // MINT FIRST: needs nothing from the database; a failure here has created nothing.
            String licenseKey = licenseKeyService.generateLicense(machine, shopName, clientCode,
                    expiryDate.toString(), currency, features, DEFAULT_PRIMARY, DEFAULT_SECONDARY,
                    "admin", adminPassword, profile);

            try {
                tx.executeWithoutResult(status -> {
                    ClientConfig c = new ClientConfig();
                    c.setClientCode(clientCode);
                    c.setAppName(shopName);
                    c.setTagline(shopName);
                    c.setLogoDark("");
                    c.setLogoLight("");
                    c.setColorPrimary(DEFAULT_PRIMARY);
                    c.setColorSecondary(DEFAULT_SECONDARY);
                    c.setAdminUsername("admin");
                    c.setAdminPassword(adminPassword);
                    c.setStoreType(storeType);
                    c.setBaseCurrency(currency);
                    c.setPhone(phone);
                    // Only trial registration ever writes these two: they are in no client DTO.
                    c.setAddress(address);
                    c.setHasTables(hasTables);
                    c.setClientStatus("TRIAL");
                    clients.save(c);

                    TrialRegistration r = new TrialRegistration();
                    r.setClientCode(clientCode);
                    r.setMachineId(machine);
                    r.setShopName(shopName);
                    r.setPhone(phone);
                    r.setAddress(address);
                    r.setStoreType(storeType);
                    r.setHasTables(hasTables);
                    r.setBaseCurrency(currency);
                    r.setRegisteredAt(LocalDateTime.now());
                    r.setExpiresAt(expiryDate.atStartOfDay());
                    trials.save(r);

                    License l = new License();
                    l.setClientCode(clientCode);
                    l.setMachineId(machine);
                    l.setClientName(shopName);
                    l.setLabel(LABEL);
                    l.setLicenseKey(licenseKey);
                    l.setStatus("ACTIVE");
                    l.setExpiresAt(expiryDate);
                    l.setDeviceInfo(device);
                    licenses.save(l);
                });
                return new TrialRegisterResponse(licenseKey);
            } catch (DataIntegrityViolationException e) {
                last = e;
                // Lost a race for this machine id: the winner's rows are committed; hand back their key.
                Optional<TrialRegistration> winner = trials.findByMachineId(machine);
                if (winner.isPresent()) {
                    return existingKey(winner.get());
                }
                // Otherwise it was a trial-N collision: loop with a fresh code (and a fresh mint).
                log.warn("Trial registration attempt {} failed on an integrity violation; retrying", attempt + 1);
            }
        }
        throw last;
    }

    /** A registration already exists for this machine: same live key back, or 409 if it has run out. */
    private TrialRegisterResponse existingKey(TrialRegistration reg) {
        if (LocalDate.now().isAfter(reg.getExpiresAt().toLocalDate())) {
            throw new TrialAlreadyUsedException();
        }
        return licenses.findByMachineIdAndClientCodeAndStatus(reg.getMachineId(), reg.getClientCode(), "ACTIVE")
                .map(l -> new TrialRegisterResponse(l.getLicenseKey()))
                .orElseThrow(TrialAlreadyUsedException::new);
    }

    private String nextClientCode() {
        long max = 0;
        for (String code : clients.findClientCodesByPrefix(CODE_PREFIX)) {
            String tail = code.substring(CODE_PREFIX.length());
            if (tail.isEmpty() || tail.length() > 12 || !tail.chars().allMatch(Character::isDigit)) {
                continue;
            }
            max = Math.max(max, Long.parseLong(tail));
        }
        return CODE_PREFIX + (max + 1);
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PW_LENGTH);
        for (int i = 0; i < PW_LENGTH; i++) {
            sb.append(PW_ALPHABET.charAt(random.nextInt(PW_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
