package com.daman.admin.service;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * The only places a store's data may live, and where a removed store's backup goes. Pure string
 * logic so the delete guard can be tested without touching a disk.
 */
public final class StorePaths {

    /** Same rule as the provisioner's identifier check: no separators, no dots, no whitespace. */
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    private static final String STORES_ROOT = "/root/stores/";
    private static final String DAMAN_ROOT = "/root/.daman/";
    private static final String REMOVED_ROOT = "/root/removed-stores/";

    private StorePaths() {}

    /**
     * True only when {@code path} is exactly {@code /root/stores/<clientCode>} or
     * {@code /root/.daman/<clientCode>}. Exact equality, never a prefix test: {@code /root/.daman}
     * itself holds the box's licence, active-client breadcrumb and machine-id cache.
     */
    public static boolean isAllowedDataDir(String path, String clientCode) {
        if (path == null || clientCode == null || !SAFE_CODE.matcher(clientCode).matches()) {
            return false;
        }
        return path.equals(STORES_ROOT + clientCode) || path.equals(DAMAN_ROOT + clientCode);
    }

    /** {@code /root/removed-stores/<clientCode>-<yyyy-MM-dd>.zip}. */
    public static String removedBackupZip(String clientCode, LocalDate date) {
        return REMOVED_ROOT + clientCode + "-" + date + ".zip";
    }
}
