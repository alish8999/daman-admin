package com.daman.admin.service;

import com.daman.admin.dto.PackageDefinitionsDto;
import com.daman.admin.dto.PackageDefinitionsDto.PackageDef;
import com.daman.admin.entity.AppSetting;
import com.daman.admin.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Reads/writes the editable package catalogue. When nothing has been saved yet
 * the built-in {@link #defaults()} are returned (and used by the client form),
 * so the system behaves exactly like the old hardcoded presets until an admin
 * customizes them.
 */
@Service
@RequiredArgsConstructor
public class PackageDefinitionService {

    private static final String SETTING_KEY = "package.definitions";

    private final AppSettingRepository repository;
    private final ObjectMapper objectMapper;

    public PackageDefinitionsDto getDefinitions() {
        return repository.findBySettingKey(SETTING_KEY)
                .map(s -> parse(s.getValueJson()))
                .orElseGet(PackageDefinitionService::defaults);
    }

    public PackageDefinitionsDto saveDefinitions(PackageDefinitionsDto dto) {
        AppSetting setting = repository.findBySettingKey(SETTING_KEY)
                .orElseGet(() -> {
                    AppSetting s = new AppSetting();
                    s.setSettingKey(SETTING_KEY);
                    return s;
                });
        try {
            setting.setValueJson(objectMapper.writeValueAsString(dto));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize package definitions", e);
        }
        repository.save(setting);
        return dto;
    }

    private PackageDefinitionsDto parse(String json) {
        if (json == null || json.isBlank()) {
            return defaults();
        }
        try {
            return objectMapper.readValue(json, PackageDefinitionsDto.class);
        } catch (Exception e) {
            return defaults();
        }
    }

    /** Built-in seed — mirrors the original hardcoded presets (tiers are cumulative). */
    private static PackageDefinitionsDto defaults() {
        List<String> basic = List.of(
                "multiLanguage", "suppliers", "clientLedger", "supplierLedger", "fractionalQuantity");
        List<String> pro = List.of(
                "multiLanguage", "suppliers", "clientLedger", "supplierLedger", "fractionalQuantity",
                "barcode", "reports", "multiCurrency",
                "accountStatement", "itemLedger", "batchStocktake", "bulkPriceUpdate", "multiCurrencyPricing");
        List<String> ultimate = List.of(
                "multiLanguage", "suppliers", "clientLedger", "supplierLedger", "fractionalQuantity",
                "barcode", "reports", "multiCurrency",
                "accountStatement", "itemLedger", "batchStocktake", "bulkPriceUpdate", "multiCurrencyPricing",
                "shifts", "productRecipes");
        return new PackageDefinitionsDto(List.of(
                new PackageDef("basic", basic),
                new PackageDef("pro", pro),
                new PackageDef("ultimate", ultimate)));
    }
}
