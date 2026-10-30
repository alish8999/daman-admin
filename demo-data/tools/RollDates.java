import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shifts every date in a demo database forward so its newest sale lands on a target day.
 *
 * <p><b>Why this runs on every reset, not once at build time.</b> A demo whose data stops six
 * months ago opens on a dashboard with no sales today, nothing this week and nothing this month —
 * a shop that looks dead, which is the opposite of what a demo is for. The existing mobile seeder
 * has exactly that problem: its data ends in April. Rolling at reset means a demo handed out in
 * March looks as alive as one handed out today.
 *
 * <p><b>Whole days only, and the same shift for every table.</b> Keeping one delta across every
 * date column preserves the relationships that make the data believable: a payment still falls
 * after its sale, a purchase still precedes the stock it created, and the busy-Thursday shape of
 * the week survives. Shifting by whole days also keeps each sale at its original time of day, so
 * the hourly pattern still looks like a real shop's trading day.
 *
 * Usage: RollDates &lt;db.sqlite&gt; [targetDate yyyy-MM-dd, default today]
 */
public class RollDates {

    public static void main(String[] args) throws Exception {
        String db = args[0];
        LocalDate target = args.length > 1 ? LocalDate.parse(args[1]) : LocalDate.now();
        String url = "jdbc:sqlite:" + db.replace("\\", "/");

        try (Connection c = DriverManager.getConnection(url)) {
            LocalDate newest = newestSaleDate(c);
            if (newest == null) { System.out.println("no sales; nothing to roll"); return; }
            long days = Duration.between(newest.atStartOfDay(), target.atStartOfDay()).toDays();
            System.out.println("newest sale " + newest + " -> target " + target + "  (shift " + days + " days)");
            if (days == 0) { System.out.println("already current"); return; }

            c.setAutoCommit(false);
            int columns = 0, rows = 0;
            for (Map.Entry<String, List<String>> e : dateColumns(c).entrySet()) {
                for (String col : e.getValue()) {
                    rows += shift(c, e.getKey(), col, days);
                    columns++;
                }
            }
            c.commit();
            System.out.println("shifted " + rows + " values across " + columns + " columns");
            LocalDate after = newestSaleDate(c);
            System.out.println("newest sale is now " + after);
        }
    }

    private static LocalDate newestSaleDate(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT MAX(sale_date) FROM sales")) {
            if (r.next() && r.getString(1) != null) {
                return LocalDate.parse(r.getString(1).substring(0, 10));
            }
        }
        return null;
    }

    /**
     * Every text column whose name looks like a timestamp, on every table.
     *
     * <p>Discovered rather than listed: this database has 50-odd tables and a hand-written list
     * would quietly miss one the next time a feature adds a table, leaving a demo whose sales are
     * current but whose journal entries are six months behind them.
     */
    private static Map<String, List<String>> dateColumns(Connection c) throws SQLException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
               + "AND name <> 'flyway_schema_history' ORDER BY name")) {
            while (r.next()) tables.add(r.getString(1));
        }
        for (String t : tables) {
            List<String> cols = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("PRAGMA table_info(\"" + t + "\")")) {
                while (r.next()) {
                    String name = r.getString("name");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith("_at") || lower.endsWith("_date") || lower.equals("date")
                            || lower.endsWith("_on")) {
                        cols.add(name);
                    }
                }
            }
            if (!cols.isEmpty()) found.put(t, cols);
        }
        return found;
    }

    /**
     * Shifts one column, leaving anything that is not a parseable timestamp untouched.
     *
     * <p>The WHERE clause matters: {@code datetime()} returns NULL for a value it cannot parse,
     * so without it a column holding a stray non-date string would be silently nulled.
     */
    private static int shift(Connection c, String table, String col, long days) {
        String sql = "UPDATE \"" + table + "\" SET \"" + col + "\" = "
                + "substr(datetime(\"" + col + "\", ?), 1, 19) "
                + "WHERE \"" + col + "\" IS NOT NULL AND datetime(\"" + col + "\") IS NOT NULL";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, (days >= 0 ? "+" : "") + days + " days");
            return p.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  skipped " + table + "." + col + ": " + e.getMessage());
            return 0;
        }
    }
}
