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

    /**
     * Where the store's SQLite file actually lives. The two store shapes differ, and assuming the
     * legacy one for a provisioned store makes a backup step find nothing (verified live 2026-10-26):
     * <ul>
     *   <li>legacy ({@code /root/.daman/<code>}): {@code <dataDir>/daman_db.sqlite}</li>
     *   <li>provisioned ({@code /root/stores/<code>}): a unit sets {@code -Duser.home=<dataDir>}, and
     *       the desktop profile resolves {@code ${user.home}/.daman/${client-code}/daman_db.sqlite},
     *       so {@code <dataDir>/.daman/<code>/daman_db.sqlite}</li>
     * </ul>
     * Throws for any other dataDir rather than guessing a shape.
     */
    public static String databaseFile(String dataDir, String clientCode) {
        if (dataDir == null || clientCode == null || !SAFE_CODE.matcher(clientCode).matches()) {
            throw new IllegalArgumentException("Cannot derive a database path for " + dataDir + " / " + clientCode);
        }
        if (dataDir.equals(DAMAN_ROOT + clientCode)) {
            return dataDir + "/daman_db.sqlite";
        }
        if (dataDir.equals(STORES_ROOT + clientCode)) {
            return dataDir + "/.daman/" + clientCode + "/daman_db.sqlite";
        }
        throw new IllegalArgumentException("Unrecognised data directory shape: " + dataDir);
    }

    /** {@code /root/removed-stores/<clientCode>-<yyyy-MM-dd>.zip}. */
    public static String removedBackupZip(String clientCode, LocalDate date) {
        return REMOVED_ROOT + clientCode + "-" + date + ".zip";
    }
}
