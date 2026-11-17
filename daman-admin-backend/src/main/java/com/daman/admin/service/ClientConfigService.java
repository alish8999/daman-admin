package com.daman.admin.service;

import com.daman.admin.dto.ClientConfigDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.dto.ClientFeaturesJson;
import com.daman.admin.dto.FeaturesRequest;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClientConfigService {

    private final ClientConfigRepository repository;
    private final ObjectMapper objectMapper;
    // Injected for the delete cascade below - clientCode is a plain string column on each of
    // these, with no foreign key to do it for us.
    private final com.daman.admin.repository.LicenseRepository licenseRepository;
    private final com.daman.admin.repository.BillingRepository billingRepository;
    private final com.daman.admin.repository.BuildLogRepository buildLogRepository;
    private final com.daman.admin.repository.TrialRegistrationRepository trialRegistrationRepository;

    @Value("${daman.workspace:D:/Daman/src}")
    private String workspaceRoot;

    public List<ClientConfigDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public ClientConfigDto findByClientCode(String clientCode) {
        return repository.findByClientCode(clientCode)
                .map(this::toDto)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
    }

    public ClientConfigDto create(ClientConfigRequest request) {
        String code = (request.getClientCode() != null && !request.getClientCode().isBlank())
                ? request.getClientCode().trim()
                : generateClientCode(request.getAppName());
        if (repository.existsByClientCode(code)) {
            throw new IllegalArgumentException("Client code already exists: " + code);
        }
        ClientConfig entity = new ClientConfig();
        entity.setClientCode(code);
        applyRequest(entity, request);
        // Features are applied HERE and not in applyRequest(), so that update() — which also
        // goes through applyRequest — cannot change them. A profile save must never be able to
        // switch a feature off: the client page's features grid writes into the same FormGroup
        // the page's generic "Save changes" button submits, so without this, flipping a feature
        // and pressing the wrong button skipped the confirmation that names what is being taken
        // away, skipped the per-licence outcome report, and stripped the flag from every live
        // key with nothing on screen but "Client saved". Changing a feature set is
        // updateFeatures(), which is the path that re-mints.
        mergeFeatures(entity, request.getFeatures());
        return toDto(repository.save(entity));
    }

    /**
     * An immutable, URL- and filesystem-safe client code (it becomes the data
     * folder name, the licence-binding key and the {@code .dat} filename, so it
     * can never change after creation). Slugifies the app name to ASCII
     * lowercase; when that yields nothing usable — e.g. an Arabic-only name —
     * falls back to {@code client-<6 hex>}. Retries on collision.
     */
    private String generateClientCode(String appName) {
        String base = (appName == null ? "" : appName).toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.length() < 2) {
            String candidate;
            do {
                candidate = "client-" + randomHex();
            } while (repository.existsByClientCode(candidate));
            return candidate;
        }
        String candidate = base;
        int n = 2;
        while (repository.existsByClientCode(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private static String randomHex() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(Integer.toHexString(r.nextInt(16)));
        return sb.toString();
    }

    public ClientConfigDto update(String clientCode, ClientConfigRequest request) {
        ClientConfig entity = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        applyRequest(entity, request);
        return toDto(repository.save(entity));
    }

    /**
     * Change ONLY this client's default feature set, leaving profile, branding and currency alone.
     *
     * <p>Exists because {@link #update} applies a whole {@code ClientConfigRequest}, so it cannot
     * serve a features-only save. The portal's new confirmed "save features" action needs one:
     * saving features re-mints licence keys, which is not something that should ride along on a
     * press of "Save changes" for a renamed shop.
     *
     * <p>Merge-patch semantics, same as {@link #update}: a null field in {@code patch} leaves that
     * flag as it was.
     */
    public ClientConfigDto updateFeatures(String clientCode, FeaturesRequest patch) {
        ClientConfig entity = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        mergeFeatures(entity, patch);
        return toDto(repository.save(entity));
    }

    /**
     * This client's stored default feature flags.
     *
     * <p>Public so a caller can tell whether a save actually changed them — the re-mint is driven
     * from the controller (see {@code ClientController}), because
     * {@link LicenceRemintService} already depends on this service to resolve entitlements and the
     * reverse edge would be a circular bean dependency.
     */
    public java.util.Map<String, Boolean> clientFeatures(String clientCode) {
        ClientConfig c = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientCode));
        return clientDefaultFeatures(c);
    }

    /** Money actually received. PENDING is an invoice nobody has paid, so it blocks nothing. */
    private static final java.util.Set<String> PAID_STATUSES = java.util.Set.of("PAID", "PARTIAL");

    /**
     * Deletes a client AND everything keyed to its client code.
     *
     * <p>This used to delete the {@code client_configs} row alone. Nothing else went, because
     * {@code clientCode} is a plain string column on the other tables with no foreign key and
     * no JPA relationship - so every delete orphaned rows in four tables. The consequences
     * were not theoretical: the client's app kept running (a signed licence never asks the
     * admin whether its client still exists), the orphaned ACTIVE licence could still be
     * pulled by the renewal endpoint while {@code doRenew} refused to touch it, the orphaned
     * trial row held that machine's unique index so it could never trial again, and - worst -
     * {@code nextClientCode()} counts only client rows, so deleting the highest-numbered
     * client let the NEXT trial be issued the same code and silently inherit the dead
     * client's licences and billing.
     *
     * <p><b>A client with real payments is refused, not cascaded.</b> Clearing out dead trials
     * is the point of this; destroying the record of a commercial relationship is not. A
     * paying client gets its licence revoked instead.
     *
     * <p>Transactional so a half-done cascade cannot leave exactly the orphans this exists to
     * prevent.
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(String clientCode) {
        ClientConfig entity = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));

        List<com.daman.admin.entity.Billing> billings =
                billingRepository.findByClientCodeOrderByCreatedAtDesc(clientCode);
        long paid = billings.stream()
                .filter(b -> b.getPaymentStatus() != null
                        && PAID_STATUSES.contains(b.getPaymentStatus().trim().toUpperCase(java.util.Locale.ROOT)))
                .count();
        if (paid > 0) {
            throw new ClientHasPaymentsException(clientCode, paid);
        }

        licenseRepository.deleteAll(licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode));
        trialRegistrationRepository.deleteAll(trialRegistrationRepository.findAllByClientCode(clientCode));
        buildLogRepository.deleteAll(buildLogRepository.findByClientCodeOrderByStartedAtDesc(clientCode));
        billingRepository.deleteAll(billings);
        repository.delete(entity);
    }

    /** The per-client inputs that vary per client and go into a v2 license payload. */
    public record LicenseEntitlements(String baseCurrency, java.util.Map<String, Boolean> features,
                                      String colorPrimary, String colorSecondary,
                                      String adminUsername, String adminPassword,
                                      LicenseProfile profile) {}

    /**
     * The CLIENT DEFAULT entitlements for {@code clientCode} — <b>deliberately not
     * licence-resolved</b>.
     *
     * <p>Only one caller may use this: {@code BuildService}, writing the bundled
     * {@code client.config.json}. A build is not a licence, and for a generic build that file is
     * all-{@code false} anyway — the signed licence is what actually grants a feature at runtime.
     *
     * <p><b>Every mint path must call {@link #licenseEntitlementsFor(License)} instead.</b> Using
     * this overload where a licence is in hand silently ignores that licence's own feature
     * override, which is exactly the silent-divergence failure the override exists to prevent.
     */
    public LicenseEntitlements licenseEntitlementsFor(String clientCode) {
        ClientConfig c = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientCode));
        return entitlements(c, clientDefaultFeatures(c));
    }

    /**
     * The entitlements that go into THIS licence's v2 payload: its client's default feature set
     * with the licence's own sparse override applied (see {@link FeatureOverride}).
     *
     * <p>A null or blank override means inherit everything, which is what every licence row
     * carries until one is deliberately customised. The override touches features only —
     * base currency, brand colours, seeded credentials and the business profile always come from
     * the client, because they describe the business rather than the installation.
     *
     * <p>Throws {@link IllegalArgumentException} for an orphan licence (one whose client row was
     * deleted; there are six such rows in production). Callers that iterate many licences must
     * catch that per row rather than let one orphan abort the batch.
     */
    public LicenseEntitlements licenseEntitlementsFor(License licence) {
        ClientConfig c = repository.findByClientCode(licence.getClientCode())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + licence.getClientCode()));
        java.util.Map<String, Boolean> resolved = FeatureOverride.merge(
                clientDefaultFeatures(c),
                FeatureOverride.parse(licence.getFeaturesOverrideJson(), objectMapper));
        return entitlements(c, resolved);
    }

    /**
     * Every feature flag the system knows about, by name.
     *
     * <p>Derived from the same {@code FeaturesDto} conversion the entitlements use, so the set a
     * caller is validated against can never drift from the set that actually ships in a licence
     * payload. Used by the per-licence override endpoint to reject an unknown key instead of
     * storing it, which is what stops a stale UI or a typo becoming a permanently inert override.
     */
    public java.util.Set<String> knownFeatureKeys() {
        return objectMapper.convertValue(exportFeatures(null),
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Boolean>>() {}).keySet();
    }

    /**
     * The client's stored flags as a plain map.
     *
     * <p>Goes through {@code exportFeatures()} → {@code FeaturesDto} and flattens with the same
     * {@code ObjectMapper} (Jackson 3), so this map and {@code client.config.json} can never
     * disagree about which flags exist — the DTO's property names ARE the camelCase config names.
     */
    private java.util.Map<String, Boolean> clientDefaultFeatures(ClientConfig c) {
        ClientConfigExportDto.FeaturesDto dto = exportFeatures(c.getFeaturesJson());
        return objectMapper.convertValue(
                dto, new tools.jackson.core.type.TypeReference<java.util.Map<String, Boolean>>() {});
    }

    /** Everything in a v2 payload except the feature map, which the caller has already decided. */
    private LicenseEntitlements entitlements(ClientConfig c, java.util.Map<String, Boolean> features) {
        String baseCurrency = (c.getBaseCurrency() == null || c.getBaseCurrency().isBlank())
                ? "USD" : c.getBaseCurrency();
        return new LicenseEntitlements(baseCurrency, features,
                c.getColorPrimary(), c.getColorSecondary(),
                c.getAdminUsername(), c.getAdminPassword(),
                new LicenseProfile(c.getAppName(), c.getPhone(), c.getAddress(),
                        c.getStoreType(), c.getHasTables()));
    }

    public ClientConfigExportDto export(String clientCode) {
        ClientConfig c = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        return buildExportDto(c);
    }

    public ClientConfigExportDto exportDefault() {
        ClientConfig c = repository.findAll().stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No client configurations found"));
        return buildExportDto(c);
    }

    private ClientConfigExportDto buildExportDto(ClientConfig c) {
        ClientConfigExportDto.DashboardDto dashboardDto = null;
        String img = c.getDashboardHeaderImage();
        if (img != null && !img.isBlank()) {
            dashboardDto = ClientConfigExportDto.DashboardDto.builder()
                    .headerImage(img)
                    .build();
        }
        return ClientConfigExportDto.builder()
                .clientCode(c.getClientCode())
                .storeType(c.getStoreType() != null ? c.getStoreType() : "mobile")
                .baseCurrency(c.getBaseCurrency() != null ? c.getBaseCurrency() : "USD")
                .appName(c.getAppName())
                .tagline(c.getTagline())
                .logo(ClientConfigExportDto.LogoDto.builder()
                        .dark(c.getLogoDark())
                        .light(c.getLogoLight())
                        .build())
                .favicon(c.getFavicon())
                .colors(ClientConfigExportDto.ColorsDto.builder()
                        .primary(c.getColorPrimary())
                        .secondary(c.getColorSecondary())
                        .success(c.getColorSuccess())
                        .danger(c.getColorDanger())
                        .warning(c.getColorWarning())
                        .info(c.getColorInfo())
                        .build())
                .footer(ClientConfigExportDto.FooterDto.builder()
                        .developer(c.getFooterDeveloper())
                        .url(c.getFooterUrl())
                        .build())
                .dashboard(dashboardDto)
                .credentials(ClientConfigExportDto.CredentialsDto.builder()
                        .username(c.getAdminUsername())
                        .password(c.getAdminPassword())
                        .build())
                .features(exportFeatures(c.getFeaturesJson()))
                .build();
    }

    private void applyRequest(ClientConfig entity, ClientConfigRequest request) {
        entity.setAppName(request.getAppName());
        // Tagline is no longer a separate field in the admin UI — the app name
        // doubles as the tagline. Honour an explicit value if a caller still
        // sends one, otherwise mirror the app name.
        entity.setTagline((request.getTagline() != null && !request.getTagline().isBlank())
                ? request.getTagline() : request.getAppName());
        // The admin UI collects a single logo; mirror it to the light slot when
        // the caller didn't send a distinct one.
        entity.setLogoDark(request.getLogoDark());
        entity.setLogoLight((request.getLogoLight() != null && !request.getLogoLight().isBlank())
                ? request.getLogoLight() : request.getLogoDark());
        // Favicon is always the bundled default now — not client-brandable.
        entity.setFavicon("favicon.ico");
        entity.setColorPrimary(request.getColorPrimary());
        entity.setColorSecondary(request.getColorSecondary());
        entity.setColorSuccess(request.getColorSuccess());
        entity.setColorDanger(request.getColorDanger());
        entity.setColorWarning(request.getColorWarning());
        entity.setColorInfo(request.getColorInfo());
        entity.setFooterDeveloper(request.getFooterDeveloper());
        entity.setFooterUrl(request.getFooterUrl());
        entity.setStoreType(request.getStoreType());
        if (request.getBaseCurrency() != null && !request.getBaseCurrency().isBlank()) {
            entity.setBaseCurrency(request.getBaseCurrency());
        }
        entity.setDashboardHeaderImage(request.getDashboardHeaderImage());
        entity.setAdminUsername(request.getAdminUsername());
        entity.setAdminPassword(request.getAdminPassword());
        entity.setPhone(request.getPhone());
        entity.setEmail(request.getEmail());
        entity.setPointOfContact(request.getPointOfContact());
        entity.setAddress(request.getAddress());
        if (request.getDefaultBuildTarget() != null && !request.getDefaultBuildTarget().isBlank()) {
            entity.setDefaultBuildTarget(request.getDefaultBuildTarget());
        }
        entity.setClientStatus(request.getClientStatus());
        entity.setClientNotes(request.getClientNotes());
    }

    private ClientFeaturesJson parseFeaturesJson(String json) {
        if (json == null || json.isBlank()) {
            return new ClientFeaturesJson(false, false, false);
        }
        try {
            return objectMapper.readValue(json, ClientFeaturesJson.class);
        } catch (Exception e) {
            return new ClientFeaturesJson(false, false, false);
        }
    }

    private void mergeFeatures(ClientConfig entity, FeaturesRequest patch) {
        ClientFeaturesJson f = parseFeaturesJson(entity.getFeaturesJson());
        if (patch != null) {
            if (patch.getMultiLanguage()   != null) f.setMultiLanguage(patch.getMultiLanguage());
            if (patch.getBarcode()         != null) f.setBarcode(patch.getBarcode());
            if (patch.getReports()         != null) f.setReports(patch.getReports());
            if (patch.getSuppliers()       != null) f.setSuppliers(patch.getSuppliers());
            if (patch.getSeedDemoData()    != null) f.setSeedDemoData(patch.getSeedDemoData());
            if (patch.getMultiCurrency()   != null) f.setMultiCurrency(patch.getMultiCurrency());
            if (patch.getShifts()          != null) f.setShifts(patch.getShifts());
            if (patch.getClientLedger()         != null) f.setClientLedger(patch.getClientLedger());
            if (patch.getSupplierLedger()       != null) f.setSupplierLedger(patch.getSupplierLedger());
            if (patch.getFractionalQuantity()   != null) f.setFractionalQuantity(patch.getFractionalQuantity());
            if (patch.getMultiCurrencyPricing() != null) f.setMultiCurrencyPricing(patch.getMultiCurrencyPricing());
            if (patch.getAccountStatement()     != null) f.setAccountStatement(patch.getAccountStatement());
            if (patch.getItemLedger()           != null) f.setItemLedger(patch.getItemLedger());
            if (patch.getBatchStocktake()       != null) f.setBatchStocktake(patch.getBatchStocktake());
            if (patch.getBulkPriceUpdate()      != null) f.setBulkPriceUpdate(patch.getBulkPriceUpdate());
            if (patch.getProductRecipes()       != null) f.setProductRecipes(patch.getProductRecipes());
            if (patch.getManufacturing()        != null) f.setManufacturing(patch.getManufacturing());
            if (patch.getUserManagement()       != null) f.setUserManagement(patch.getUserManagement());
            if (patch.getInvoiceSettings()      != null) f.setInvoiceSettings(patch.getInvoiceSettings());
            if (patch.getQuotation()            != null) f.setQuotation(patch.getQuotation());
            if (patch.getAccounting()           != null) f.setAccounting(patch.getAccounting());
            if (patch.getCurrencyExchange()     != null) f.setCurrencyExchange(patch.getCurrencyExchange());
            if (patch.getKitchenPrinter()       != null) f.setKitchenPrinter(patch.getKitchenPrinter());
            if (patch.getScaleBarcodes()        != null) f.setScaleBarcodes(patch.getScaleBarcodes());
            if (patch.getAutoBackup()           != null) f.setAutoBackup(patch.getAutoBackup());
            if (patch.getPosTerminals()          != null) f.setPosTerminals(patch.getPosTerminals());
            if (patch.getMobilePos()             != null) f.setMobilePos(patch.getMobilePos());
            if (patch.getSimulatePosMode()       != null) f.setSimulatePosMode(patch.getSimulatePosMode());
            if (patch.getTableOrders()          != null) f.setTableOrders(patch.getTableOrders());
            if (patch.getQuickPickCards()        != null) f.setQuickPickCards(patch.getQuickPickCards());
            if (patch.getCafeMode()             != null) f.setCafeMode(patch.getCafeMode());
            if (patch.getConsignment()          != null) f.setConsignment(patch.getConsignment());
            if (patch.getShareholders()         != null) f.setShareholders(patch.getShareholders());
            if (patch.getFixedAssets()          != null) f.setFixedAssets(patch.getFixedAssets());
            if (patch.getDeviceRepair()         != null) f.setDeviceRepair(patch.getDeviceRepair());
            if (patch.getGoogleDriveBackup()    != null) f.setGoogleDriveBackup(patch.getGoogleDriveBackup());
        }
        try {
            entity.setFeaturesJson(objectMapper.writeValueAsString(f));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize features", e);
        }
    }

    private ClientConfigExportDto.FeaturesDto exportFeatures(String json) {
        ClientFeaturesJson f = parseFeaturesJson(json);
        return ClientConfigExportDto.FeaturesDto.builder()
                .multiLanguage(f.isMultiLanguage())
                .barcode(f.isBarcode())
                .reports(f.isReports())
                .suppliers(f.isSuppliers())
                .seedDemoData(f.isSeedDemoData())
                .multiCurrency(f.isMultiCurrency())
                .shifts(f.isShifts())
                .clientLedger(f.isClientLedger())
                .supplierLedger(f.isSupplierLedger())
                .fractionalQuantity(f.isFractionalQuantity())
                .multiCurrencyPricing(f.isMultiCurrencyPricing())
                .accountStatement(f.isAccountStatement())
                .itemLedger(f.isItemLedger())
                .batchStocktake(f.isBatchStocktake())
                .bulkPriceUpdate(f.isBulkPriceUpdate())
                .productRecipes(f.isProductRecipes())
                .manufacturing(f.isManufacturing())
                .userManagement(f.isUserManagement())
                .invoiceSettings(f.isInvoiceSettings())
                .quotation(f.isQuotation())
                .accounting(f.isAccounting())
                .currencyExchange(f.isCurrencyExchange())
                .kitchenPrinter(f.isKitchenPrinter())
                .scaleBarcodes(f.isScaleBarcodes())
                .autoBackup(f.isAutoBackup())
                .posTerminals(f.isPosTerminals())
                .mobilePos(f.isMobilePos())
                .simulatePosMode(f.isSimulatePosMode())
                .tableOrders(f.isTableOrders())
                .quickPickCards(f.isQuickPickCards())
                .cafeMode(f.isCafeMode())
                .consignment(f.isConsignment())
                .shareholders(f.isShareholders())
                .fixedAssets(f.isFixedAssets())
                .deviceRepair(f.isDeviceRepair())
                .googleDriveBackup(f.isGoogleDriveBackup())
                .build();
    }

    private ClientConfigDto.FeaturesDto toDtoFeatures(String json) {
        ClientFeaturesJson f = parseFeaturesJson(json);
        return ClientConfigDto.FeaturesDto.builder()
                .multiLanguage(f.isMultiLanguage())
                .barcode(f.isBarcode())
                .reports(f.isReports())
                .suppliers(f.isSuppliers())
                .seedDemoData(f.isSeedDemoData())
                .multiCurrency(f.isMultiCurrency())
                .shifts(f.isShifts())
                .clientLedger(f.isClientLedger())
                .supplierLedger(f.isSupplierLedger())
                .fractionalQuantity(f.isFractionalQuantity())
                .multiCurrencyPricing(f.isMultiCurrencyPricing())
                .accountStatement(f.isAccountStatement())
                .itemLedger(f.isItemLedger())
                .batchStocktake(f.isBatchStocktake())
                .bulkPriceUpdate(f.isBulkPriceUpdate())
                .productRecipes(f.isProductRecipes())
                .manufacturing(f.isManufacturing())
                .userManagement(f.isUserManagement())
                .invoiceSettings(f.isInvoiceSettings())
                .quotation(f.isQuotation())
                .accounting(f.isAccounting())
                .currencyExchange(f.isCurrencyExchange())
                .kitchenPrinter(f.isKitchenPrinter())
                .scaleBarcodes(f.isScaleBarcodes())
                .autoBackup(f.isAutoBackup())
                .posTerminals(f.isPosTerminals())
                .mobilePos(f.isMobilePos())
                .simulatePosMode(f.isSimulatePosMode())
                .tableOrders(f.isTableOrders())
                .quickPickCards(f.isQuickPickCards())
                .cafeMode(f.isCafeMode())
                .consignment(f.isConsignment())
                .shareholders(f.isShareholders())
                .fixedAssets(f.isFixedAssets())
                .deviceRepair(f.isDeviceRepair())
                .googleDriveBackup(f.isGoogleDriveBackup())
                .build();
    }

    /**
     * Writes the three dev-checkout config files: {@code client.config.json} into both
     * the backend resources and the frontend assets, and {@code client-meta.properties}
     * into the backend resources. Shared by {@link #prepareDevConfig(String)} and
     * {@code DevRunService} (per-client + generic dev-run modes) so the exact set of
     * files, and their locations, can never drift between the two callers.
     */
    void writeCheckoutConfig(String configJson, String clientMetaBody) throws IOException {
        Path workspace = Paths.get(workspaceRoot);
        Path backendJson  = workspace.resolve("daman-backend/src/main/resources/client.config.json");
        Path frontendJson = workspace.resolve("daman-frontend/src/assets/client.config.json");
        Path backendMeta  = workspace.resolve("daman-backend/src/main/resources/client-meta.properties");
        Files.createDirectories(backendJson.getParent());
        Files.createDirectories(frontendJson.getParent());
        Files.writeString(backendJson, configJson);
        Files.writeString(frontendJson, configJson);
        Files.writeString(backendMeta, clientMetaBody);
    }

    public record PrepareConfigResult(String backendPath, String frontendPath) {}

    public PrepareConfigResult prepareDevConfig(String clientCode) {
        ClientConfigExportDto exportDto = export(clientCode);
        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportDto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize config: " + e.getMessage(), e);
        }

        Path workspace = Paths.get(workspaceRoot);
        Path backendDest = workspace.resolve("daman-backend/src/main/resources/client.config.json");
        Path frontendDest = workspace.resolve("daman-frontend/src/assets/client.config.json");
        // Mirror BuildService.writeClientMeta: keep the dev workspace's
        // client-meta.properties in sync with the currently-prepared client,
        // otherwise LicenseService keeps validating against whatever clientCode
        // the previous `mvn package` happened to bake in.
        Path runtimePropsDest = Paths.get(System.getProperty("user.home"), ".daman", "runtime.properties");
        String runtimeProps = "daman.runtime.client-code=" + clientCode + System.lineSeparator();
        String clientMeta = String.join("\n",
                "# Auto-generated by ClientConfigService (prepare-config). Do not edit by hand.",
                "# Used by LicenseService to bind this binary to a specific client.",
                "client.code=" + clientCode,
                "client.version=dev",
                "client.builtAt=" + LocalDateTime.now()
        ) + "\n";

        try {
            writeCheckoutConfig(json, clientMeta);
            Files.createDirectories(runtimePropsDest.getParent());
            Files.writeString(runtimePropsDest, runtimeProps);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config files: " + e.getMessage(), e);
        }

        return new PrepareConfigResult(backendDest.toString(), frontendDest.toString());
    }

    private ClientConfigDto toDto(ClientConfig c) {
        return ClientConfigDto.builder()
                .id(c.getId())
                .clientCode(c.getClientCode())
                .appName(c.getAppName())
                .tagline(c.getTagline())
                .logoDark(c.getLogoDark())
                .logoLight(c.getLogoLight())
                .favicon(c.getFavicon())
                .colorPrimary(c.getColorPrimary())
                .colorSecondary(c.getColorSecondary())
                .colorSuccess(c.getColorSuccess())
                .colorDanger(c.getColorDanger())
                .colorWarning(c.getColorWarning())
                .colorInfo(c.getColorInfo())
                .footerDeveloper(c.getFooterDeveloper())
                .footerUrl(c.getFooterUrl())
                .storeType(c.getStoreType())
                .baseCurrency(c.getBaseCurrency() != null ? c.getBaseCurrency() : "USD")
                .dashboardHeaderImage(c.getDashboardHeaderImage())
                .adminUsername(c.getAdminUsername())
                .adminPassword(c.getAdminPassword())
                .phone(c.getPhone())
                .email(c.getEmail())
                .pointOfContact(c.getPointOfContact())
                .address(c.getAddress())
                .defaultBuildTarget(c.getDefaultBuildTarget() != null ? c.getDefaultBuildTarget() : "win")
                .features(toDtoFeatures(c.getFeaturesJson()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .clientStatus(c.getClientStatus())
                .clientNotes(c.getClientNotes())
                .build();
    }
}
