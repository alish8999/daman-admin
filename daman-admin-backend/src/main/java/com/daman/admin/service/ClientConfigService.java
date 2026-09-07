package com.daman.admin.service;

import com.daman.admin.dto.ClientConfigDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.dto.ClientFeaturesJson;
import com.daman.admin.dto.FeaturesRequest;
import com.daman.admin.entity.ClientConfig;
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
        if (repository.existsByClientCode(request.getClientCode())) {
            throw new IllegalArgumentException("Client code already exists: " + request.getClientCode());
        }
        ClientConfig entity = new ClientConfig();
        entity.setClientCode(request.getClientCode());
        applyRequest(entity, request);
        return toDto(repository.save(entity));
    }

    public ClientConfigDto update(String clientCode, ClientConfigRequest request) {
        ClientConfig entity = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        applyRequest(entity, request);
        return toDto(repository.save(entity));
    }

    public void delete(String clientCode) {
        ClientConfig entity = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        repository.delete(entity);
    }

    /** The per-client inputs that vary per client and go into a v2 license payload. */
    public record LicenseEntitlements(String baseCurrency, java.util.Map<String, Boolean> features,
                                      String colorPrimary, String colorSecondary) {}

    /**
     * Builds the {@code baseCurrency} + {@code features} that go into a v2 license
     * payload for {@code clientCode}, straight from that client's stored config —
     * the same source {@code client.config.json} is generated from, so the two can
     * never disagree about which flags exist.
     */
    public LicenseEntitlements licenseEntitlementsFor(String clientCode) {
        ClientConfig c = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientCode));
        String baseCurrency = (c.getBaseCurrency() == null || c.getBaseCurrency().isBlank())
                ? "USD" : c.getBaseCurrency();
        // Reuse exportFeatures() → FeaturesDto, then flatten to a plain Map<String,Boolean>
        // via the same ObjectMapper (Jackson 3). The DTO's property names ARE the
        // client.config.json camelCase names.
        ClientConfigExportDto.FeaturesDto dto = exportFeatures(c.getFeaturesJson());
        java.util.Map<String, Boolean> features = objectMapper.convertValue(
                dto, new tools.jackson.core.type.TypeReference<java.util.Map<String, Boolean>>() {});
        return new LicenseEntitlements(baseCurrency, features,
                c.getColorPrimary(), c.getColorSecondary());
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
        entity.setTagline(request.getTagline());
        entity.setLogoDark(request.getLogoDark());
        entity.setLogoLight(request.getLogoLight());
        entity.setFavicon(request.getFavicon());
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
        if (request.getDefaultBuildTarget() != null && !request.getDefaultBuildTarget().isBlank()) {
            entity.setDefaultBuildTarget(request.getDefaultBuildTarget());
        }
        entity.setClientStatus(request.getClientStatus());
        entity.setClientNotes(request.getClientNotes());
        mergeFeatures(entity, request.getFeatures());
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
            if (patch.getSimulatePosMode()       != null) f.setSimulatePosMode(patch.getSimulatePosMode());
            if (patch.getTableOrders()          != null) f.setTableOrders(patch.getTableOrders());
            if (patch.getQuickPickCards()        != null) f.setQuickPickCards(patch.getQuickPickCards());
            if (patch.getCafeMode()             != null) f.setCafeMode(patch.getCafeMode());
            if (patch.getConsignment()          != null) f.setConsignment(patch.getConsignment());
            if (patch.getShareholders()         != null) f.setShareholders(patch.getShareholders());
            if (patch.getFixedAssets()          != null) f.setFixedAssets(patch.getFixedAssets());
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
                .simulatePosMode(f.isSimulatePosMode())
                .tableOrders(f.isTableOrders())
                .quickPickCards(f.isQuickPickCards())
                .cafeMode(f.isCafeMode())
                .consignment(f.isConsignment())
                .shareholders(f.isShareholders())
                .fixedAssets(f.isFixedAssets())
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
                .simulatePosMode(f.isSimulatePosMode())
                .tableOrders(f.isTableOrders())
                .quickPickCards(f.isQuickPickCards())
                .cafeMode(f.isCafeMode())
                .consignment(f.isConsignment())
                .shareholders(f.isShareholders())
                .fixedAssets(f.isFixedAssets())
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
                .defaultBuildTarget(c.getDefaultBuildTarget() != null ? c.getDefaultBuildTarget() : "win")
                .features(toDtoFeatures(c.getFeaturesJson()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .clientStatus(c.getClientStatus())
                .clientNotes(c.getClientNotes())
                .build();
    }
}
