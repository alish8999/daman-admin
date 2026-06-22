package com.daman.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The editable package catalogue: an ordered list of packages, each mapping to
 * the set of feature keys it enables. Stored as JSON in
 * {@code app_settings} under the key {@code package.definitions}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PackageDefinitionsDto {

    private List<PackageDef> packages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageDef {
        /** Stable key — also the i18n key suffix (package.basic / package.pro / ...). */
        private String key;
        /** Feature keys enabled by this package. */
        private List<String> features;
    }
}
