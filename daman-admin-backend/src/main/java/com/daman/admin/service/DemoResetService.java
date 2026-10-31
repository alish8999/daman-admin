package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Wipes a demo slot back to its pristine seed and rolls its dates to today.
 *
 * <p><b>It can only ever touch a demo slot.</b> {@link HostedInstance#isDemo()} is the boundary and
 * it is checked before anything is stopped or copied. The operation destroys a database, so the
 * guard is a hard refusal rather than a confirm dialog: there is no request shape, no client code
 * and no flag a caller can send that resets {@code viora}.
 *
 * <p>Sequence, in this order for reasons that have already bitten once each:
 * <ol>
 *   <li>Stop the unit. Copying over a live SQLite file corrupts it.</li>
 *   <li>Delete {@code -wal} and {@code -shm}. Leave them and SQLite replays the old journal over
 *       the seed that was just copied in, silently restoring the previous demo's data.</li>
 *   <li>Copy the seed.</li>
 *   <li>Roll the dates, preserving each column's stored format. Writing a timestamp into a
 *       date-only column makes the entity's converter throw at boot and the store crash-loops.</li>
 *   <li>Start, and report whether it came back healthy.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetService {

    /** Thrown when the row is not a demo slot, or is not in a state that can be reset. */
    public static class RefusedException extends RuntimeException {
        public RefusedException(String message) { super(message); }
    }

    public record ResetResult(String clientCode, String flavour, boolean healthy, String message) {}

    private final HostedInstanceRepository repository;
    private final ServerOps ops;

    /** Where the pristine seeds live, one per flavour, as {@code <dir>/<flavour>.sqlite}. */
    @Value("${daman.demo.seed-dir:/root/demo-seeds}")
    private String seedDir;

    public ResetResult reset(String clientCode) {
        HostedInstance row = repository.findByClientCode(clientCode)
                .orElseThrow(() -> new RefusedException("No hosted store named '" + clientCode + "'."));

        // The guard. Before anything is stopped, copied or deleted.
        if (!row.isDemo()) {
            log.warn("Refused a demo reset for '{}': not a demo slot", clientCode);
            throw new RefusedException("'" + clientCode + "' is not a demo slot. Reset destroys the "
                    + "store's data and is only ever offered for slots created as demos.");
        }
        if ("PROVISIONING".equals(row.getStatus())) {
            throw new RefusedException("'" + clientCode + "' is still provisioning.");
        }

        String flavour = row.getDemoFlavour();
        Path seed = seedFor(flavour);
        if (!Files.isRegularFile(seed)) {
            throw new RefusedException("No seed for flavour '" + flavour + "' at " + seed
                    + ". Build it with demo-data/tools/BuildMartDemo.java and upload it.");
        }

        Path db = databaseOf(row);
        boolean wasSuspended = "SUSPENDED".equals(row.getStatus());

        ops.stopAndDisable(row.getServiceName());
        try {
            // The journal, before the copy — see the class note.
            Files.deleteIfExists(Path.of(db + "-wal"));
            Files.deleteIfExists(Path.of(db + "-shm"));
            Files.createDirectories(db.getParent());
            Files.copy(seed, db, StandardCopyOption.REPLACE_EXISTING);
            int shifted = rollDatesToToday(db);
            log.info("Demo reset '{}': seeded from {} and shifted {} date values", clientCode, seed, shifted);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the seed for '" + clientCode + "': " + e.getMessage(), e);
        } finally {
            // A slot that was suspended stays suspended: resetting is not a reason to start paying
            // ~800 MB of RAM for a store nobody has been handed yet.
            if (!wasSuspended) ops.enableAndStart(row.getServiceName());
        }

        if (wasSuspended) {
            return new ResetResult(clientCode, flavour, false,
                    "Reset from the " + flavour + " seed. The slot stays suspended — resume it when you hand it out.");
        }
        boolean healthy = ops.health(row.getPort());
        return new ResetResult(clientCode, flavour, healthy, healthy
                ? "Reset from the " + flavour + " seed and back up."
                : "Reset from the " + flavour + " seed, but it has not reported healthy yet. "
                  + "A store takes about a minute to start; check the row again shortly.");
    }

    Path seedFor(String flavour) {
        String safe = flavour == null ? "" : flavour.trim().toLowerCase(Locale.ROOT);
        // The flavour reaches a filesystem path, so it is whitelisted rather than sanitised.
        if (!safe.equals("mart") && !safe.equals("mobile")) {
            throw new RefusedException("Unknown demo flavour '" + flavour + "'. Expected 'mart' or 'mobile'.");
        }
        return Path.of(seedDir, safe + ".sqlite");
    }

    /** Mirrors where a hosted store actually keeps its database: {@code <dataDir>/.daman/<code>/}. */
    Path databaseOf(HostedInstance row) {
        return Path.of(row.getDataDir(), ".daman", row.getClientCode(), "daman_db.sqlite");
    }

    // ── Date roll ────────────────────────────────────────────────────────────────────────────

    /**
     * Shifts every date forward by whole days so the newest sale lands today.
     *
     * <p>Whole days, and the same shift everywhere, so a payment still falls after its sale and
     * each sale keeps its time of day. Without this a demo opens on a dashboard showing no sales
     * today, nothing this week and nothing this month — a shop that looks dead.
     */
    int rollDatesToToday(Path db) {
        String url = "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url)) {
            LocalDate newest = newestSaleDate(c);
            if (newest == null) return 0;
            long days = ChronoUnit.DAYS.between(newest, LocalDate.now());
            if (days == 0) return 0;

            Map<String, Map<Integer, Integer>> before = shapes(c);
            c.setAutoCommit(false);
            int rows = 0;
            for (Map.Entry<String, List<String>> e : dateColumns(c).entrySet()) {
                for (String col : e.getValue()) rows += shift(c, e.getKey(), col, days);
            }
            c.commit();
            c.setAutoCommit(true);

            // A changed shape means a LocalDate column just became a timestamp, which the entity's
            // converter rejects on first read — fatally, at boot. Refuse to hand back a database
            // that would crash-loop the store.
            Map<String, Map<Integer, Integer>> after = shapes(c);
            for (Map.Entry<String, Map<Integer, Integer>> e : before.entrySet()) {
                if (!e.getValue().equals(after.get(e.getKey()))) {
                    throw new IllegalStateException("Date roll changed the stored format of "
                            + e.getKey() + "; refusing to start a store that would crash on it.");
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not roll the demo's dates: " + e.getMessage(), e);
        }
    }

    private LocalDate newestSaleDate(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT MAX(sale_date) FROM sales")) {
            if (r.next() && r.getString(1) != null) return LocalDate.parse(r.getString(1).substring(0, 10));
        } catch (SQLException e) {
            return null; // a seed with no sales table is not a seed worth rolling
        }
        return null;
    }

    /** Found by scanning, not from a list: a hand-written list silently misses a new table. */
    private Map<String, List<String>> dateColumns(Connection c) throws SQLException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' "
                     + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name")) {
            while (r.next()) tables.add(r.getString(1));
        }
        for (String t : tables) {
            List<String> cols = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("PRAGMA table_info(\"" + t + "\")")) {
                while (r.next()) {
                    String n = r.getString("name").toLowerCase(Locale.ROOT);
                    if (n.endsWith("_at") || n.endsWith("_date") || n.equals("date") || n.endsWith("_on")) {
                        cols.add(r.getString("name"));
                    }
                }
            }
            if (!cols.isEmpty()) found.put(t, cols);
        }
        return found;
    }

    private Map<String, Map<Integer, Integer>> shapes(Connection c) throws SQLException {
        Map<String, Map<Integer, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : dateColumns(c).entrySet()) {
            for (String col : e.getValue()) {
                Map<Integer, Integer> tally = new TreeMap<>();
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT length(\"" + col + "\") L, COUNT(*) FROM \""
                             + e.getKey() + "\" WHERE \"" + col + "\" IS NOT NULL GROUP BY L")) {
                    while (r.next()) tally.put(r.getInt(1), r.getInt(2));
                } catch (SQLException ignored) { continue; }
                out.put(e.getKey() + "." + col, tally);
            }
        }
        return out;
    }

    private int shift(Connection c, String table, String col, long days) {
        String sql = "UPDATE \"" + table + "\" SET \"" + col + "\" = "
                + "CASE WHEN length(\"" + col + "\") = 10 "
                + "     THEN substr(datetime(\"" + col + "\", ?), 1, 10) "
                + "     ELSE substr(datetime(\"" + col + "\", ?), 1, 19) END "
                + "WHERE \"" + col + "\" IS NOT NULL AND datetime(\"" + col + "\") IS NOT NULL";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            String delta = (days >= 0 ? "+" : "") + days + " days";
            p.setString(1, delta);
            p.setString(2, delta);
            return p.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }
}
